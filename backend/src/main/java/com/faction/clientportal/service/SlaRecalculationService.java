package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityComment;
import com.faction.clientportal.repository.VulnerabilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Recalculates one workflow's open findings' stored SLA dates after that workflow's SLAs are edited,
 * and returns a finding marked Past Due whose new due date is no longer past to Open. An SLA edit
 * recalculates only that workflow's open findings; the admin repair path recalculates every workflow.
 *
 * <p>Without this, lengthening an SLA would never clear Past Due: {@code VulnerabilityPastDueJob}
 * only ever moves findings into that status. Findings are walked in keyset batches by id, each batch
 * in its own transaction, so a run over a million findings neither holds one huge transaction nor
 * loads every finding at once.
 *
 * <p>{@code VulnerabilityRepository#findOpenInWorkflowAfterId} and
 * {@code #findOpenOutsideWorkflowsAfterId} lock each batch's rows ({@code SELECT ... FOR UPDATE}) for
 * the life of that batch's transaction, since {@code Vulnerability} has no optimistic
 * ({@code @Version}) locking: without a lock, a user closing a finding or posting a comment between
 * this batch's read and its commit would have their change silently overwritten by the batch's stale
 * copy on save. A concurrent user save on a locked row simply waits for the batch's (short)
 * transaction to commit rather than being lost, and the default batch size is kept modest (500) so
 * that wait stays brief.
 */
@Service
@Slf4j
public class SlaRecalculationService {

    static final String PAST_DUE = "Past Due";
    static final String OPEN = "Open";
    static final String CLEARED_PAST_DUE_COMMENT =
            "**Status changed** from *Past Due* to *Open* — SLA changed; no longer past due";

    /**
     * Total attempts (including the first) for a single batch before it is given up on. A
     * {@link TransientDataAccessException} — which covers {@code ConcurrencyFailureException} and its
     * subtypes {@code CannotAcquireLockException}, {@code PessimisticLockingFailureException} and
     * deadlock losers — is retried from the same cursor in a fresh transaction; any other exception is
     * not retried.
     */
    static final int MAX_BATCH_ATTEMPTS = 3;

    /** Counts from one run: findings whose dates changed, and findings returned from Past Due to Open. */
    public record RecalculationResult(int recalculated, int clearedPastDue) {
    }

    private record BatchOutcome(int size, String lastId, int recalculated, int clearedPastDue) {
    }

    private final VulnerabilityRepository vulnerabilityRepository;
    private final SlaService slaService;
    private final WorkflowCatalogService workflowCatalogService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final boolean recalculateOnConfigChange;

    public SlaRecalculationService(
            VulnerabilityRepository vulnerabilityRepository,
            SlaService slaService,
            WorkflowCatalogService workflowCatalogService,
            TransactionTemplate transactionTemplate,
            @Value("${faction.sla.recalculation-batch-size:500}") int batchSize,
            @Value("${faction.sla.recalculate-on-config-change:true}") boolean recalculateOnConfigChange) {
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.slaService = slaService;
        this.workflowCatalogService = workflowCatalogService;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.recalculateOnConfigChange = recalculateOnConfigChange;
    }

    @Async("slaRecalculationExecutor")
    @EventListener
    public void onSlaConfigChanged(SlaConfigChangedEvent event) {
        if (!recalculateOnConfigChange) {
            log.debug("SLA changed; recalculation of stored due dates is disabled by configuration");
            return;
        }
        recalculateAndLog(event.workflowId());
    }

    /**
     * The admin repair path ({@code POST /api/v1/config/assessment-workflow/recalculate-sla}): the same
     * recalculation an SLA edit triggers, on the same single-thread executor, whatever
     * {@code faction.sla.recalculate-on-config-change} says. Re-saving unchanged SLAs publishes no
     * event, so this is how a run that failed after its batch retries is repeated. The controller calls
     * it through the Spring proxy, so {@code @Async} applies. Recalculates every workflow.
     */
    @Async("slaRecalculationExecutor")
    public void recalculateInBackground() {
        recalculateAndLog(null);
    }

    private void recalculateAndLog(String workflowId) {
        try {
            RecalculationResult result = workflowId == null
                    ? recalculateOpenFindings() : recalculateOpenFindings(workflowId);
            log.info("SLA recalculation ({}): {} open finding(s) got new due dates, {} returned from Past Due to Open",
                    workflowId == null ? "all workflows" : "workflow " + workflowId,
                    result.recalculated(), result.clearedPastDue());
        } catch (Exception e) {
            log.error("SLA recalculation failed: {}", e.getMessage(), e);
        }
    }

    /** Recalculates every workflow's open findings now, on the calling thread. */
    public RecalculationResult recalculateOpenFindings() {
        int recalculated = 0;
        int clearedPastDue = 0;
        for (AssessmentWorkflow workflow : workflowCatalogService.load().workflows(true)) {
            RecalculationResult result = recalculateOpenFindings(workflow.getId());
            recalculated += result.recalculated();
            clearedPastDue += result.clearedPastDue();
        }
        return new RecalculationResult(recalculated, clearedPastDue);
    }

    /** Recalculates the open findings of the workflow {@code workflowId} resolves to, on the calling thread. */
    public RecalculationResult recalculateOpenFindings(String workflowId) {
        WorkflowCatalog catalog = workflowCatalogService.load();
        AssessmentWorkflow target = catalog.forId(workflowId);
        boolean isDefault = target == catalog.defaultWorkflow();
        List<String> others = catalog.workflows(true).stream()
                .map(AssessmentWorkflow::getId)
                .filter(id -> !id.equals(target.getId()))
                .toList();
        BatchScope scope = new BatchScope(target.getId(), isDefault, others);

        LocalDateTime now = LocalDateTime.now();
        String afterId = "";
        int recalculated = 0;
        int clearedPastDue = 0;
        while (true) {
            String cursor = afterId;
            BatchOutcome outcome = executeBatchWithRetry(scope, cursor, now);
            if (outcome == null || outcome.size() == 0) {
                break;
            }
            recalculated += outcome.recalculated();
            clearedPastDue += outcome.clearedPastDue();
            if (outcome.size() < batchSize) {
                break;
            }
            afterId = outcome.lastId();
        }
        return new RecalculationResult(recalculated, clearedPastDue);
    }

    /**
     * Which findings one run covers: the target workflow's, or Default Workflow's (every finding not on
     * another workflow; every open finding when no other workflow exists).
     */
    private record BatchScope(String workflowId, boolean isDefault, List<String> otherWorkflowIds) {
    }

    /**
     * Runs {@link #recalculateBatch} for {@code afterId} in a fresh transaction, retrying up to
     * {@link #MAX_BATCH_ATTEMPTS} times in total (same cursor, new transaction each time) when the
     * failure is a {@link TransientDataAccessException}. Any other exception propagates immediately.
     * If the final attempt still fails, logs at ERROR with the cursor and attempt count, then rethrows
     * so the private {@code recalculateAndLog(String)} helper — called by both {@link #onSlaConfigChanged}
     * and {@link #recalculateInBackground} — logs the run as failed.
     */
    private BatchOutcome executeBatchWithRetry(BatchScope scope, String afterId, LocalDateTime now) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return transactionTemplate.execute(status -> recalculateBatch(scope, afterId, now));
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_BATCH_ATTEMPTS) {
                    log.error("SLA recalculation: batch after id '{}' failed after {} attempt(s), giving up: {}",
                            afterId, attempt, e.getMessage(), e);
                    throw e;
                }
                log.warn("SLA recalculation: batch after id '{}' failed on attempt {} of {}, retrying: {}",
                        afterId, attempt, MAX_BATCH_ATTEMPTS, e.getMessage());
            }
        }
    }

    private BatchOutcome recalculateBatch(BatchScope scope, String afterId, LocalDateTime now) {
        List<Vulnerability> batch = loadBatch(scope, afterId);
        if (batch.isEmpty()) {
            return new BatchOutcome(0, afterId, 0, 0);
        }

        List<LocalDateTime> dueBefore = new ArrayList<>(batch.size());
        List<LocalDateTime> warningBefore = new ArrayList<>(batch.size());
        for (Vulnerability v : batch) {
            dueBefore.add(v.getDueAt());
            warningBefore.add(v.getWarningAt());
        }

        // Read only now that the rows are locked, so an SLA edit that committed while this batch waited is used.
        slaService.refreshAll(batch, workflowCatalogService.load().forId(scope.workflowId()));

        List<Vulnerability> changed = new ArrayList<>();
        int recalculated = 0;
        int clearedPastDue = 0;
        for (int i = 0; i < batch.size(); i++) {
            Vulnerability v = batch.get(i);
            boolean datesChanged = !Objects.equals(dueBefore.get(i), v.getDueAt())
                    || !Objects.equals(warningBefore.get(i), v.getWarningAt());
            boolean noLongerPastDue = PAST_DUE.equals(v.getStatus())
                    && (v.getDueAt() == null || v.getDueAt().isAfter(now));
            if (datesChanged) {
                recalculated++;
            }
            if (noLongerPastDue) {
                returnToOpen(v, now);
                clearedPastDue++;
            }
            if (datesChanged || noLongerPastDue) {
                changed.add(v);
            }
        }
        if (!changed.isEmpty()) {
            vulnerabilityRepository.saveAll(changed);
        }
        return new BatchOutcome(batch.size(), batch.get(batch.size() - 1).getId(), recalculated, clearedPastDue);
    }

    private List<Vulnerability> loadBatch(BatchScope scope, String afterId) {
        PageRequest page = PageRequest.of(0, batchSize);
        if (!scope.isDefault()) {
            return vulnerabilityRepository.findOpenInWorkflowAfterId(afterId, scope.workflowId(), page);
        }
        if (scope.otherWorkflowIds().isEmpty()) {
            return vulnerabilityRepository.findOpenAfterId(afterId, page);
        }
        return vulnerabilityRepository.findOpenOutsideWorkflowsAfterId(afterId, scope.otherWorkflowIds(), page);
    }

    /** Same comment shape VulnerabilityPastDueJob writes when it marks a finding Past Due. */
    private static void returnToOpen(Vulnerability v, LocalDateTime now) {
        v.setStatus(OPEN);
        VulnerabilityComment systemComment = VulnerabilityComment.builder()
                .id(UUID.randomUUID().toString())
                .authorId("system")
                .authorName("System")
                .content(CLEARED_PAST_DUE_COMMENT)
                .systemGenerated(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (v.getComments() == null) {
            v.setComments(new ArrayList<>());
        }
        v.getComments().add(systemComment);
        v.setLastUpdatedBy("system");
        v.setUpdatedAt(now);
    }
}
