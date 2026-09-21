package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A named assessment workflow: the assessment statuses, SLAs, vulnerability statuses, remediation
 * stages and peer review setting an assessment type's assessments use. Default Workflow (id
 * {@value #DEFAULT_ID}) always exists; bootstrap creates it on a new installation and the
 * assessment_workflows migration copies the old single configuration into it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assessment_workflows", indexes = {
    @Index(name = "idx_assessment_workflows_name", columnList = "name", unique = true)
})
public class AssessmentWorkflow {

    public static final String DEFAULT_ID = "default";
    public static final String DEFAULT_NAME = "Default Workflow";

    /** The status a new assessment starts in when nothing else has set one. */
    public static final String DEFAULT_NEW_STATUS = "New";

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    /** True only for Default Workflow, which can never be deleted or archived. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultWorkflow;

    /** Hidden from pickers and default filter lists; its assessments keep working. */
    private boolean archived;

    /** Ordered list of available assessment status labels */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> statuses = new ArrayList<>(Arrays.asList(
            "New", "Scheduling", "Data Gathering", "Planning",
            "Testing", "Reporting", "Completed", "NA"
    ));

    /** Status applied when a new assessment is created */
    @Builder.Default
    private String newAssessmentStatus = DEFAULT_NEW_STATUS;

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

    /** Vulnerability statuses added to the built-in ones (None/Open/Closed/Past Due are implicit) */
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

    /**
     * Whether the person who submitted an assessment for peer review may also
     * review it. Off by default — the point of peer review is a second pair of
     * eyes. super_admin is exempt from this check.
     */
    @Builder.Default
    private boolean allowSelfPeerReview = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Default SLAs seeded on new instances; severities can be adjusted/removed in Assessment Config. */
    public static List<VulnerabilitySla> defaultVulnerabilitySlas() {
        return new ArrayList<>(Arrays.asList(
                new VulnerabilitySla("CRITICAL", 30, 20),
                new VulnerabilitySla("HIGH", 60, 30),
                new VulnerabilitySla("MEDIUM", 365, 300)
        ));
    }

    /** Default stages seeded on new instances; editable in Assessment Config. */
    public static List<RemediationStage> defaultRemediationStages() {
        return new ArrayList<>(Arrays.asList(
                new RemediationStage("development", "Development"),
                new RemediationStage("staging", "Staging"),
                new RemediationStage("production", "Production")
        ));
    }

    /** A builder for Default Workflow with every setting at its default. */
    public static AssessmentWorkflowBuilder defaultWorkflowBuilder() {
        return builder().id(DEFAULT_ID).name(DEFAULT_NAME).defaultWorkflow(true);
    }
}
