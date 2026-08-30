package com.faction.clientportal.service;

import com.faction.clientportal.model.MentionTargetType;

/**
 * The "where and who" behind a notification: which item it concerns, who caused it, and
 * what was said. Captured on the notification row so the mentions dashboard can group by
 * item type and show the comment itself, instead of parsing the deep link and loading
 * three other entities per row.
 *
 * <p>{@code contentHtml} is the raw comment body; {@link NotificationService} strips and
 * truncates it into the stored excerpt, so callers pass what they already have.
 */
public record NotificationContext(
        MentionTargetType targetType,
        String targetId,
        String targetName,
        String actorUsername,
        String actorName,
        String contentHtml) {
}
