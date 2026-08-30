package com.faction.clientportal.model;

import lombok.*;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "mention_queue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MentionQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Who was @mentioned */
    private String mentionedUsername;

    /** Who wrote the content containing the @mention */
    private String mentionedByUsername;

    /** Unique per comment — used as dedup key (e.g. /assessments/abc?comment=xyz) */
    private String contextLink;

    /**
     * Snapshot of the content the mention appeared in, so the email can show what was
     * actually said instead of just "you were mentioned". Snapshotted rather than looked
     * up at send time because comments live in a JSON column on their parent and are not
     * individually addressable.
     */
    @Column(columnDefinition = "TEXT")
    private String contentHtml;

    /** Decides whether the email may carry a reply token. Null on rows queued before Phase 2. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private MentionTargetType targetType;

    /** Application id, vulnerability id, or notebook node id, per {@link #targetType}. */
    private String targetId;

    /** Set for vulnerability mentions, whose service API is assessment-scoped. */
    private String assessmentId;

    /**
     * Name of the application, finding, or note the mention was written on. Captured at
     * queue time so the in-app notification can say where the mention was without the
     * drain re-loading the item — which for a since-deleted item it could not do anyway.
     */
    private String targetName;

    /** When the notification should be sent (now + 30 seconds when queued) */
    private LocalDateTime scheduledFor;

    @Builder.Default
    private boolean processed = false;

    private LocalDateTime processedAt;

    private LocalDateTime createdAt;
}
