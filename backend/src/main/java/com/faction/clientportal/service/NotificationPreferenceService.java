package com.faction.clientportal.service;

import com.faction.clientportal.dto.NotificationPreferenceDto;
import com.faction.clientportal.dto.UpdateNotificationPreferencesRequest;
import com.faction.clientportal.model.NotificationCategory;
import com.faction.clientportal.model.NotificationPreference;
import com.faction.clientportal.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes per-user notification opt-outs.
 *
 * <p>Absent means enabled, everywhere. That single rule is what makes this safe to ship
 * without a backfill: no existing user's behaviour changes, and a category added later
 * defaults on for everyone until they say otherwise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;

    /** Every category, with stored values where they exist and defaults where they don't. */
    public List<NotificationPreferenceDto> getForUser(String username) {
        Map<NotificationCategory, NotificationPreference> stored = new EnumMap<>(NotificationCategory.class);
        repository.findByUsername(username).forEach(p -> stored.put(p.getCategory(), p));

        List<NotificationPreferenceDto> out = new ArrayList<>();
        for (NotificationCategory category : NotificationCategory.values()) {
            NotificationPreference p = stored.get(category);
            out.add(NotificationPreferenceDto.of(
                    category,
                    p == null || p.isInAppEnabled(),
                    p == null || p.isEmailEnabled()));
        }
        return out;
    }

    public List<NotificationPreferenceDto> update(String username,
                                                  UpdateNotificationPreferencesRequest request) {
        if (request.getPreferences() != null) {
            request.getPreferences().forEach(update -> {
                if (update.getCategory() == null) return;
                NotificationPreference existing = repository
                        .findByUsernameAndCategory(username, update.getCategory())
                        .orElseGet(() -> NotificationPreference.builder()
                                .username(username)
                                .category(update.getCategory())
                                .build());
                if (update.getInAppEnabled() != null) existing.setInAppEnabled(update.getInAppEnabled());
                if (update.getEmailEnabled() != null) existing.setEmailEnabled(update.getEmailEnabled());
                repository.save(existing);
            });
        }
        return getForUser(username);
    }

    /**
     * Whether an in-app notification of this type should be recorded and pushed.
     *
     * <p>Note this suppresses the record entirely rather than hiding it at read time —
     * a user who has switched a category off should not accumulate unread counts for it.
     */
    public boolean isInAppEnabled(String username, String type) {
        return lookup(username, type).map(NotificationPreference::isInAppEnabled).orElse(true);
    }

    public boolean isEmailEnabled(String username, String type) {
        return lookup(username, type).map(NotificationPreference::isEmailEnabled).orElse(true);
    }

    private java.util.Optional<NotificationPreference> lookup(String username, String type) {
        if (username == null) return java.util.Optional.empty();
        try {
            return repository.findByUsernameAndCategory(username, NotificationCategory.forType(type));
        } catch (Exception e) {
            // A preference lookup must never be the reason a notification is lost.
            log.warn("Could not read notification preferences for {}: {}", username, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
