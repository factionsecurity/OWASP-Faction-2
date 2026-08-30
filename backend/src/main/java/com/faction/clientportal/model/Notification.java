package com.faction.clientportal.model;

import lombok.*;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notifications_username", columnList = "username")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String username; // JWT username of the recipient

    private String title;
    private String message;

    /** Type constants: ASSESSMENT_CREATED, ASSESSOR_ASSIGNED, RETEST_ASSIGNED */
    private String type;

    /** Frontend route to navigate to when clicked, e.g. /assessments/abc123 */
    private String link;

    /**
     * What the notification is about, so the mentions dashboard can group rows by the
     * kind of item rather than parsing {@link #link}. Null on rows recorded before this
     * was captured, and on notifications that are not tied to one item.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private MentionTargetType targetType;

    /** Application id, vulnerability id, or notebook node id, per {@link #targetType}. */
    private String targetId;

    /**
     * Snapshot of the item's name at notification time. Snapshotted rather than joined:
     * the list has to render without loading three other entities per row, and a renamed
     * item should still read as it did when the message arrived.
     */
    private String targetName;

    /** Who caused the notification — the commenter or the person who @mentioned you. */
    private String actorUsername;

    /** Display name of {@link #actorUsername} at notification time. */
    private String actorName;

    /** Plain-text snippet of what was said, so the list shows the comment, not just its author. */
    @Column(columnDefinition = "TEXT")
    private String excerpt;

    @Builder.Default
    private boolean read = false;

    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
