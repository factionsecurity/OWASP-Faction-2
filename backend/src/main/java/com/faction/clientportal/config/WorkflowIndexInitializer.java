package com.faction.clientportal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Builds the indexes that per-workflow reads and guards use, concurrently, on every boot:
 * <ul>
 *   <li>{@code assessments (workflow_id, status)}: which assessments of a workflow are in a status
 *       (status-in-use guards, renames) and the recalculation's per-workflow assessment check.</li>
 *   <li>{@code vulnerabilities (assessment_id, status)}: findings of a workflow's assessments in a
 *       vulnerability status (guards and renames).</li>
 *   <li>{@code vulnerability_stage_completions (stage_id)}: whether a remediation stage is in use.</li>
 * </ul>
 *
 * <p>Not a Flyway migration for the reasons given on {@link VulnerabilitySlaIndexInitializer}: a
 * concurrent build cannot run in a transaction, and fresh installs get their tables from Hibernate
 * after Flyway has run.
 */
@Component
@Slf4j
public class WorkflowIndexInitializer {

    static final String ASSESSMENT_WORKFLOW_STATUS_INDEX = "idx_assessments_workflow_status";
    static final String VULNERABILITY_ASSESSMENT_STATUS_INDEX = "idx_vulnerabilities_assessment_status";
    static final String STAGE_COMPLETION_STAGE_INDEX = "idx_vulnerability_stage_completions_stage";

    private final ConcurrentIndexBuilder indexBuilder;
    private final boolean enabled;

    public WorkflowIndexInitializer(
            ConcurrentIndexBuilder indexBuilder,
            @Value("${faction.workflows.index-initializer.enabled:true}") boolean enabled) {
        this.indexBuilder = indexBuilder;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("Workflow index initializer disabled by configuration");
            return;
        }
        ensureIndexes();
    }

    /** Builds or repairs the three indexes. */
    public void ensureIndexes() {
        indexBuilder.ensure(ASSESSMENT_WORKFLOW_STATUS_INDEX, "ON assessments (workflow_id, status)");
        indexBuilder.ensure(VULNERABILITY_ASSESSMENT_STATUS_INDEX, "ON vulnerabilities (assessment_id, status)");
        indexBuilder.ensure(STAGE_COMPLETION_STAGE_INDEX, "ON vulnerability_stage_completions (stage_id)");
    }
}
