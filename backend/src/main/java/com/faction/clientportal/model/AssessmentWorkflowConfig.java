package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Entity;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Table;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Id;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton configuration document for the assessment workflow.
 * Stored with a fixed ID of "singleton" in the database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assessment_workflow_config")
public class AssessmentWorkflowConfig {

    @Id
    private String id; // always "singleton"

    /** Ordered list of available assessment status labels */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> statuses = Arrays.asList(
            "New", "Scheduling", "Data Gathering", "Planning",
            "Testing", "Reporting", "Completed", "NA"
    );

    /** Status applied when a new assessment is created */
    @Builder.Default
    private String newAssessmentStatus = "New";

    /** Status applied when an assessment falls within its start/end date window */
    @Builder.Default
    private String inProgressStatus = "Testing";

    /** Status applied when an assessment is finalized */
    @Builder.Default
    private String completedStatus = "Completed";

    /** Optional hex color per status label, e.g. {"New": "#22c55e"} */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> statusColors = new HashMap<>();

    /** SLA deadlines for opened vulnerabilities, keyed by severity */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<VulnerabilitySla> vulnerabilitySlas = defaultVulnerabilitySlas();

    /** Default SLAs seeded on new instances; severities can be adjusted/removed in Assessment Config. */
    public static List<VulnerabilitySla> defaultVulnerabilitySlas() {
        return new ArrayList<>(Arrays.asList(
                new VulnerabilitySla("CRITICAL", 30, 20),
                new VulnerabilitySla("HIGH", 60, 30),
                new VulnerabilitySla("MEDIUM", 365, 300)
        ));
    }

    /** Custom vulnerability status labels added by the user (defaults None/Open/Closed/Past Due are implicit) */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> vulnerabilityStatuses = new ArrayList<>();

    /**
     * Ordered remediation stages a fix moves through (e.g. Development → Staging → Production).
     * The <em>last</em> stage is terminal: completing it closes the vulnerability outright
     * (status {@code Closed} + {@code closedAt}); completing any earlier stage records a
     * {@link VulnerabilityStageCompletion} and leaves the finding open. Stages are completable
     * in any order — no sequence is enforced — and completions never affect the SLA clock.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<RemediationStage> remediationStages = defaultRemediationStages();

    /** Default stages seeded on new instances; editable in Assessment Config. */
    public static List<RemediationStage> defaultRemediationStages() {
        return new ArrayList<>(Arrays.asList(
                new RemediationStage("development", "Development"),
                new RemediationStage("staging", "Staging"),
                new RemediationStage("production", "Production")
        ));
    }

    /**
     * Whether the person who submitted an assessment for peer review may also
     * review it. Off by default — the point of peer review is a second pair of
     * eyes. super_admin is exempt from this check.
     */
    @Builder.Default
    private boolean allowSelfPeerReview = false;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemediationStage {
        /**
         * Stable identifier completions are keyed by. Never changes once assigned, so renaming
         * a stage re-labels its historical completions instead of orphaning them.
         */
        private String id;
        /** Display name, free text (e.g. "QA", "UAT"). */
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VulnerabilitySla {
        /** Vulnerability severity (e.g. CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL) */
        private String severity;
        /** Days after openedAt before the vulnerability is considered past due (the SLA deadline) */
        private int pastDueDays;
        /**
         * Lead time in days before the past-due deadline at which the vulnerability starts showing a
         * warning — the warning window opens at {@code openedAt + (pastDueDays - warningDays)}.
         */
        private int warningDays;
    }
}
