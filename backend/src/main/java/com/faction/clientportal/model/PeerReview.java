package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "peer_reviews", indexes = {
    @Index(name = "idx_peer_reviews_assessmentid", columnList = "assessment_id")
})
public class PeerReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String assessmentId;

    /** Assessment-level field values at submission time (key = fieldId) */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> snapshotFieldValues = new HashMap<>();

    /** Reviewer's edits to assessment-level fields (key = fieldId) */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> revisedFieldValues = new HashMap<>();

    /** Reviewer notes per assessment-level field (key = fieldId) */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> fieldNotes = new HashMap<>();

    /** Snapshotted vulnerabilities with reviewer edits and notes */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<PeerReviewVulnerability> vulnerabilities = new ArrayList<>();

    private String submittedByUserId;

    /**
     * Whoever claimed the review. Kept as the review's owner of record — it is what the queue
     * sorts on — but it is no longer the whole answer: a review can be worked by several people
     * at once, and {@link #reviewerUserIds} is the full set.
     */
    private String reviewedByUserId;

    /**
     * Everyone who has actually worked this review, in the order they first did, starting with
     * whoever claimed it. Appended to when a reviewer saves, so it records participation rather
     * than intent — opening the review without editing does not put you here.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> reviewerUserIds = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    @Builder.Default
    private PeerReviewStatus status = PeerReviewStatus.PENDING;
}
