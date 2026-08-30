package com.faction.clientportal.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One user's opt-out for one notification category.
 *
 * <p>Rows are written only when someone changes something: an absent row means enabled.
 * That keeps existing behaviour identical for every user who never visits the settings
 * page, and avoids backfilling a row per user per category on migration.
 *
 * <p>Keyed by username rather than user id, matching {@code Notification} and the whole
 * mention pipeline, which key on the JWT subject.
 */
@Entity
@Table(name = "notification_preference",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_notification_preference_user_category",
               columnNames = {"username", "category"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private NotificationCategory category;

    /** The bell and the live stream. */
    @Builder.Default
    @Column(nullable = false)
    private boolean inAppEnabled = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean emailEnabled = true;
}
