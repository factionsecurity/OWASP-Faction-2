package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.AssessmentWorkflowConfig.VulnerabilitySla;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilityComment;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Editing an SLA recalculates open findings' stored due dates and returns a finding that is no
 * longer past due to Open.
 *
 * <p>The async listener is switched off in the test profile, so these tests call
 * {@link SlaRecalculationService#recalculateOpenFindings()} directly and nothing runs in the
 * background.
 */
@SpringBootTest
@ActiveProfiles("test")
class SlaRecalculationServiceTest extends TestContainersConfig {

    @Autowired private SlaRecalculationService recalculationService;
    @Autowired private AssessmentWorkflowConfigService workflowConfigService;
    @Autowired private AssessmentWorkflowConfigRepository workflowConfigRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private SlaService slaService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        workflowConfigRepository.deleteAll();
    }

    @Test
    void lengtheningAnSlaMovesDueDatesAndReturnsFindingsNoLongerPastDueToOpen() {
        workflowConfigRepository.save(AssessmentWorkflowConfig.builder()
                .id("singleton")
                .vulnerabilitySlas(new ArrayList<>(List.of(new VulnerabilitySla("HIGH", 30, 20))))
                .build());

        LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime recentOpened = base.minusDays(45);
        LocalDateTime oldOpened = base.minusDays(90);
        // Under HIGH 30/20 both were due 30 days after opening and both are marked Past Due.
        Vulnerability recent = seed(b -> b.status("Past Due").openedAt(recentOpened)
                .dueAt(recentOpened.plusDays(30)).warningAt(recentOpened.plusDays(10)));
        Vulnerability old = seed(b -> b.status("Past Due").openedAt(oldOpened)
                .dueAt(oldOpened.plusDays(30)).warningAt(oldOpened.plusDays(10)));
        Vulnerability closed = seed(b -> b.status("Closed").openedAt(recentOpened).closedAt(base));

        AssessmentWorkflowConfig edited = workflowConfigService.getConfig();
        edited.setVulnerabilitySlas(new ArrayList<>(List.of(new VulnerabilitySla("HIGH", 60, 30))));
        workflowConfigService.updateConfig(edited);

        SlaRecalculationService.RecalculationResult result = recalculationService.recalculateOpenFindings();

        assertThat(result).isEqualTo(new SlaRecalculationService.RecalculationResult(2, 1));

        // Now due 15 days from today: no longer past due.
        Vulnerability recentAfter = reload(recent);
        assertThat(recentAfter.getDueAt()).isEqualTo(recentOpened.plusDays(60));
        assertThat(recentAfter.getWarningAt()).isEqualTo(recentOpened.plusDays(30));
        assertThat(recentAfter.getStatus()).isEqualTo("Open");
        assertThat(recentAfter.getComments()).hasSize(1);
        VulnerabilityComment comment = recentAfter.getComments().get(0);
        assertThat(comment.getContent())
                .isEqualTo("**Status changed** from *Past Due* to *Open* — SLA changed; no longer past due");
        assertThat(comment.getAuthorId()).isEqualTo("system");
        assertThat(comment.getAuthorName()).isEqualTo("System");
        assertThat(comment.isSystemGenerated()).isTrue();

        // Due 30 days ago even under 60 days: still past due, dates moved, no comment.
        Vulnerability oldAfter = reload(old);
        assertThat(oldAfter.getDueAt()).isEqualTo(oldOpened.plusDays(60));
        assertThat(oldAfter.getWarningAt()).isEqualTo(oldOpened.plusDays(30));
        assertThat(oldAfter.getStatus()).isEqualTo("Past Due");
        assertThat(oldAfter.getComments()).isEmpty();

        Vulnerability closedAfter = reload(closed);
        assertThat(closedAfter.getStatus()).isEqualTo("Closed");
        assertThat(closedAfter.getDueAt()).isNull();
        assertThat(closedAfter.getComments()).isEmpty();
    }

    @Test
    void removingAnSlaClearsPastDueFindingWithNullDueDates() {
        // Characterization test: with no SLA nothing can be past due, so a Past Due finding whose
        // severity's SLA is removed must be returned to Open with dueAt/warningAt cleared to null.
        workflowConfigRepository.save(AssessmentWorkflowConfig.builder()
                .id("singleton")
                .vulnerabilitySlas(new ArrayList<>(List.of(new VulnerabilitySla("HIGH", 30, 20))))
                .build());

        // 2099 so the every-minute past-due job never touches it.
        LocalDateTime openedAt = LocalDateTime.of(2099, 1, 1, 9, 0);
        Vulnerability pastDue = seed(b -> b.status("Past Due").openedAt(openedAt)
                .dueAt(openedAt.plusDays(30)).warningAt(openedAt.plusDays(10)));

        AssessmentWorkflowConfig edited = workflowConfigService.getConfig();
        edited.setVulnerabilitySlas(new ArrayList<>());
        workflowConfigService.updateConfig(edited);

        SlaRecalculationService.RecalculationResult result = recalculationService.recalculateOpenFindings();

        assertThat(result).isEqualTo(new SlaRecalculationService.RecalculationResult(1, 1));

        Vulnerability after = reload(pastDue);
        assertThat(after.getStatus()).isEqualTo("Open");
        assertThat(after.getDueAt()).isNull();
        assertThat(after.getWarningAt()).isNull();
        assertThat(after.getComments()).hasSize(1);
        assertThat(after.getComments().get(0).getContent())
                .isEqualTo("**Status changed** from *Past Due* to *Open* — SLA changed; no longer past due");
    }

    @Test
    void processesEveryOpenFindingAcrossBatches() {
        // Defaults (no config row): HIGH 60/30. Five findings in batches of two: 2, 2, 1.
        List<Vulnerability> seeded = seedOpenHigh(5);

        SlaRecalculationService.RecalculationResult result = smallBatches().recalculateOpenFindings();

        assertThat(result).isEqualTo(new SlaRecalculationService.RecalculationResult(5, 0));
        assertAllHaveHighDefaults(seeded);
    }

    @Test
    void processesEveryOpenFindingWhenTheCountIsAnExactMultipleOfTheBatchSize() {
        List<Vulnerability> seeded = seedOpenHigh(4);

        SlaRecalculationService.RecalculationResult result = smallBatches().recalculateOpenFindings();

        assertThat(result).isEqualTo(new SlaRecalculationService.RecalculationResult(4, 0));
        assertAllHaveHighDefaults(seeded);
    }

    @Test
    void aSecondRunChangesNothing() {
        seedOpenHigh(3);
        recalculationService.recalculateOpenFindings();

        assertThat(recalculationService.recalculateOpenFindings())
                .isEqualTo(new SlaRecalculationService.RecalculationResult(0, 0));
    }

    @Test
    void lockingTheBatchReadKeepsAConcurrentEditWaitingUntilTheBatchCommits() {
        // 2099 so the every-minute past-due job never touches it.
        Vulnerability seeded = seed(b -> b.status("Open").openedAt(LocalDateTime.of(2099, 1, 1, 9, 0)));

        transactionTemplate.executeWithoutResult(status -> {
            vulnerabilityRepository.findOpenAfterId("", PageRequest.of(0, 10));
            // Row is locked (SELECT ... FOR UPDATE) for the life of this transaction: a concurrent
            // NOWAIT lock attempt from another connection must fail rather than silently succeed and
            // let a user's own edit be overwritten when this batch's transaction later commits.
            assertThat(canLockWithoutWaiting(seeded.getId())).isFalse();
        });

        // Batch transaction committed and released its lock: the same NOWAIT attempt now succeeds.
        assertThat(canLockWithoutWaiting(seeded.getId())).isTrue();
    }

    /** Attempts {@code SELECT ... FOR UPDATE NOWAIT} on a separate, autocommit JDBC connection. */
    private boolean canLockWithoutWaiting(String id) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM vulnerabilities WHERE id = ? FOR UPDATE NOWAIT")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            } catch (SQLException lockNotAvailable) {
                // 55P03 = lock_not_available (the NOWAIT failure). Any other SQLState is unexpected
                // and must not be mistaken for a successful lock check.
                if (!"55P03".equals(lockNotAvailable.getSQLState())) {
                    throw new RuntimeException(lockNotAvailable);
                }
                return false;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SlaRecalculationService smallBatches() {
        return new SlaRecalculationService(vulnerabilityRepository, slaService, transactionTemplate, 2, true);
    }

    private List<Vulnerability> seedOpenHigh(int count) {
        List<Vulnerability> seeded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Opened in 2099 so the every-minute past-due job never touches these.
            seeded.add(seed(b -> b.status("Open").openedAt(LocalDateTime.of(2099, 1, 1, 9, 0))));
        }
        return seeded;
    }

    private void assertAllHaveHighDefaults(List<Vulnerability> seeded) {
        for (Vulnerability v : seeded) {
            Vulnerability stored = reload(v);
            assertThat(stored.getDueAt()).isEqualTo(LocalDateTime.of(2099, 3, 2, 9, 0));
            assertThat(stored.getWarningAt()).isEqualTo(LocalDateTime.of(2099, 1, 31, 9, 0));
        }
    }

    private Vulnerability seed(UnaryOperator<Vulnerability.VulnerabilityBuilder> extra) {
        Vulnerability.VulnerabilityBuilder b = Vulnerability.builder()
                .name("Finding " + System.nanoTime()).severity(VulnerabilitySeverity.HIGH)
                .assessmentId("assessment-1").order(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now());
        return vulnerabilityRepository.save(extra.apply(b).build());
    }

    private Vulnerability reload(Vulnerability v) {
        return vulnerabilityRepository.findById(v.getId()).orElseThrow();
    }
}
