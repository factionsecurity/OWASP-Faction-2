package com.faction.clientportal.model;

import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "retests", indexes = {
    @Index(name = "idx_retests_vulnerability_deleted", columnList = "vulnerability_id, deleted_at"),
    @Index(name = "idx_retests_assessment_deleted", columnList = "assessment_id, deleted_at"),
    @Index(name = "idx_retests_assignee_deleted", columnList = "assigned_assessor_ids, deleted_at")
})
public class Retest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String vulnerabilityId;
    private String assessmentId;
    private String applicationId;

    private LocalDateTime scheduledStartDate;
    private LocalDateTime scheduledEndDate;

    /**
     * When the retest was verified complete. Written only by {@code RetestService.complete},
     * which makes it the completion timestamp the activity log reports on — nothing else
     * touches it, including the generic update path.
     */
    private LocalDateTime closedDate;

    /**
     * Username of whoever verified the result, stamped once at completion. A username rather
     * than an id to match {@link #createdBy} and {@link #lastUpdatedBy}, which carry the JWT
     * subject — {@code assignedAssessorIds} is the odd one out here, not this.
     *
     * <p>Separate from {@link #lastUpdatedBy}, which any later edit overwrites: "who signed off
     * on this retest" has to survive someone correcting a comment afterwards, or the activity
     * log credits the wrong person.
     */
    private String completedBy;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> assignedAssessorIds = new ArrayList<>();

    @Builder.Default
    private String status = "SCHEDULED"; // SCHEDULED, IN_PROGRESS, PASSED, FAILED, CANCELLED

    private String result; // PASS or FAIL

    /** Freeform HTML from a rich text editor — needs TEXT, not the default varchar(255). */
    @Column(columnDefinition = "TEXT")
    private String comment;

    /** Freeform HTML from a rich text editor — needs TEXT, not the default varchar(255). */
    @Column(columnDefinition = "TEXT")
    private String scope;

    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
