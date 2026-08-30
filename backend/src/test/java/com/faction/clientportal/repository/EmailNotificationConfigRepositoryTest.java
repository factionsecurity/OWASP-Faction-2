package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.EmailNotificationConfig;
import com.faction.clientportal.model.EmailNotificationConfig.EventSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the JSONB round-trip the service tests mock away. The per-event settings map
 * is the only place this application stores a map of a nested type in a JSON column, and
 * that is exactly the shape Hibernate needs help serialising.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailNotificationConfigRepositoryTest extends TestContainersConfig {

    @Autowired
    private EmailNotificationConfigRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void savesAndReloadsThePerEventSettingsMap() {
        Map<String, EventSettings> events = new HashMap<>();
        events.put("VULNERABILITY_PAST_DUE", EventSettings.builder()
                .notifyStakeholders(true)
                .notifyAppOwner(true)
                .customMessage("Please schedule remediation.")
                .build());
        events.put("VULNERABILITY_CLOSED:production", EventSettings.builder()
                .includeMentionedUsers(true)
                .build());

        repository.save(EmailNotificationConfig.builder()
                .id(EmailNotificationConfig.SINGLETON_ID)
                .enabled(true)
                .events(events)
                .pastDueRepeatCount(2)
                .pastDueRepeatIntervalDays(7)
                .build());

        EmailNotificationConfig reloaded =
                repository.findById(EmailNotificationConfig.SINGLETON_ID).orElseThrow();

        assertThat(reloaded.isEnabled()).isTrue();
        assertThat(reloaded.getPastDueRepeatCount()).isEqualTo(2);
        assertThat(reloaded.getEvents()).hasSize(2);

        EventSettings pastDue = reloaded.settingsFor("VULNERABILITY_PAST_DUE");
        assertThat(pastDue.isNotifyStakeholders()).isTrue();
        assertThat(pastDue.isNotifyAppOwner()).isTrue();
        assertThat(pastDue.getCustomMessage()).isEqualTo("Please schedule remediation.");

        assertThat(reloaded.settingsFor("VULNERABILITY_CLOSED:production").isIncludeMentionedUsers())
                .isTrue();
    }

    @Test
    void anEmptySettingsMapRoundTrips() {
        repository.save(EmailNotificationConfig.builder()
                .id(EmailNotificationConfig.SINGLETON_ID)
                .build());

        EmailNotificationConfig reloaded =
                repository.findById(EmailNotificationConfig.SINGLETON_ID).orElseThrow();

        assertThat(reloaded.getEvents()).isEmpty();
        assertThat(reloaded.isEnabled()).isFalse();
    }

    /**
     * Regression test for the shape of row this bug already wrote into live databases:
     * {@code isAnyAudienceEnabled()} read as a Jackson property, so the derived value was
     * persisted and then made the whole config unreadable. Rows written before the fix
     * must still load rather than needing a hand-run UPDATE.
     */
    @Test
    void aRowCarryingAStrayDerivedFieldStillLoads() {
        jdbcTemplate.update(
                "INSERT INTO email_notification_config "
                        + "(id, enabled, events, past_due_repeat_count, past_due_repeat_interval_days) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?)",
                EmailNotificationConfig.SINGLETON_ID, true,
                "{\"VULNERABILITY_PAST_DUE\":{\"notifyStakeholders\":true,"
                        + "\"notifyAssessors\":false,\"notifyAppOwner\":false,"
                        + "\"includeMentionedUsers\":false,\"customMessage\":null,"
                        + "\"anyAudienceEnabled\":true}}",
                0, 7);

        EmailNotificationConfig reloaded =
                repository.findById(EmailNotificationConfig.SINGLETON_ID).orElseThrow();

        assertThat(reloaded.settingsFor("VULNERABILITY_PAST_DUE").isNotifyStakeholders()).isTrue();
        assertThat(reloaded.settingsFor("VULNERABILITY_PAST_DUE").isAnyAudienceEnabled()).isTrue();
    }
}
