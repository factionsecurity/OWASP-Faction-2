package com.faction.clientportal.service;

import com.faction.clientportal.dto.NotificationPreferenceDto;
import com.faction.clientportal.dto.UpdateNotificationPreferencesRequest;
import com.faction.clientportal.model.NotificationCategory;
import com.faction.clientportal.model.NotificationPreference;
import com.faction.clientportal.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The load-bearing rule here is "absent means enabled". It is what lets this ship without
 * a backfill and without changing behaviour for anyone who never opens the settings page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationPreferenceServiceTest {

    @Mock private NotificationPreferenceRepository repository;

    @InjectMocks private NotificationPreferenceService service;

    @Test
    void withNoStoredRows_everythingIsEnabled() {
        when(repository.findByUsername("alice")).thenReturn(List.of());
        when(repository.findByUsernameAndCategory(any(), any())).thenReturn(Optional.empty());

        assertThat(service.getForUser("alice"))
                .hasSize(NotificationCategory.values().length)
                .allMatch(NotificationPreferenceDto::isInAppEnabled)
                .allMatch(NotificationPreferenceDto::isEmailEnabled);

        assertThat(service.isInAppEnabled("alice", "MENTION")).isTrue();
        assertThat(service.isEmailEnabled("alice", "MENTION")).isTrue();
    }

    @Test
    void aStoredOptOutIsHonouredPerChannel() {
        when(repository.findByUsernameAndCategory("alice", NotificationCategory.MENTION))
                .thenReturn(Optional.of(NotificationPreference.builder()
                        .username("alice").category(NotificationCategory.MENTION)
                        .inAppEnabled(true).emailEnabled(false).build()));

        // Muting email must not also mute the bell.
        assertThat(service.isEmailEnabled("alice", "MENTION")).isFalse();
        assertThat(service.isInAppEnabled("alice", "MENTION")).isTrue();
    }

    @Test
    void categoriesMapFromTheTypeStringsActuallyInUse() {
        assertThat(NotificationCategory.forType("MENTION")).isEqualTo(NotificationCategory.MENTION);
        assertThat(NotificationCategory.forType("ASSESSOR_ASSIGNED"))
                .isEqualTo(NotificationCategory.ASSESSMENT_ASSIGNED);
        assertThat(NotificationCategory.forType("ASSESSMENT_CREATED"))
                .isEqualTo(NotificationCategory.ASSESSMENT_ASSIGNED);
        assertThat(NotificationCategory.forType("RETEST_ASSIGNED"))
                .isEqualTo(NotificationCategory.RETEST_ASSIGNED);
    }

    @Test
    void anUnknownTypeFallsBackToOtherRatherThanBeingUnswitchable() {
        assertThat(NotificationCategory.forType("SOMETHING_NEW")).isEqualTo(NotificationCategory.OTHER);
        assertThat(NotificationCategory.forType(null)).isEqualTo(NotificationCategory.OTHER);
    }

    @Test
    void updatingOneChannelLeavesTheOtherAlone() {
        NotificationPreference stored = NotificationPreference.builder()
                .username("alice").category(NotificationCategory.MENTION)
                .inAppEnabled(true).emailEnabled(true).build();
        when(repository.findByUsernameAndCategory("alice", NotificationCategory.MENTION))
                .thenReturn(Optional.of(stored));
        when(repository.findByUsername("alice")).thenReturn(List.of(stored));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        UpdateNotificationPreferencesRequest.Item item = new UpdateNotificationPreferencesRequest.Item();
        item.setCategory(NotificationCategory.MENTION);
        item.setEmailEnabled(false);      // inAppEnabled deliberately null
        request.setPreferences(List.of(item));

        service.update("alice", request);

        assertThat(stored.isEmailEnabled()).isFalse();
        assertThat(stored.isInAppEnabled()).isTrue();
    }

    @Test
    void aFailedLookupDefaultsToSending() {
        // A preference lookup must never be the reason a notification is lost.
        when(repository.findByUsernameAndCategory(any(), any()))
                .thenThrow(new RuntimeException("database is down"));

        assertThat(service.isInAppEnabled("alice", "MENTION")).isTrue();
        assertThat(service.isEmailEnabled("alice", "MENTION")).isTrue();
    }
}
