package com.faction.clientportal.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton routing table for outbound notification email: for each event, which
 * audiences hear about it and what the message says.
 *
 * <p>Settings live in one JSON column keyed by
 * {@link EmailNotificationEvent#key(String)} rather than as a column per switch. The
 * per-stage "vulnerability closed" events are defined by whatever remediation stages the
 * instance has configured, so the set of keys is not knowable at schema-design time;
 * a column-per-switch table would need a migration every time someone renames a stage.
 *
 * <p>Every switch defaults <em>off</em>. These emails go to stakeholders and app owners —
 * people outside the platform — so an upgrade must never start mailing them because a
 * default said so.
 */
@Entity
@Table(name = "email_notification_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailNotificationConfig {

    public static final String SINGLETON_ID = "singleton";

    @Id
    @Builder.Default
    private String id = SINGLETON_ID;

    /**
     * Master switch. Off means no event email is sent at all, whatever the individual
     * settings say — one place to stop the noise without losing the configuration.
     */
    @Builder.Default
    private boolean enabled = false;

    /** Per-event settings, keyed by {@link EmailNotificationEvent#key(String)}. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, EventSettings> events = new HashMap<>();

    /**
     * How many times a past-due digest is re-sent after the first one. 0 means a finding
     * is reported once when it breaches its SLA and never again.
     */
    @Builder.Default
    private int pastDueRepeatCount = 0;

    /** Days to wait between past-due repeats. Ignored when {@link #pastDueRepeatCount} is 0. */
    @Builder.Default
    private int pastDueRepeatIntervalDays = 7;

    public Map<String, EventSettings> getEvents() {
        return events == null ? new HashMap<>() : new HashMap<>(events);
    }

    public void setEvents(Map<String, EventSettings> events) {
        this.events = events == null ? new HashMap<>() : new HashMap<>(events);
    }

    /**
     * Settings for one event. Absent from the map means every audience is off.
     *
     * <p>{@code ignoreUnknown} because this is stored as JSON: a field dropped in a later
     * version must not make every existing row unreadable, which would take the whole
     * config down rather than just losing one setting.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventSettings {

        @Builder.Default
        private boolean notifyAssessors = false;

        @Builder.Default
        private boolean notifyStakeholders = false;

        @Builder.Default
        private boolean notifyAppOwner = false;

        /** Whether users @mentioned in the item's comments are copied in. */
        @Builder.Default
        private boolean includeMentionedUsers = false;

        /** Whether everyone with organizational access to the application is copied in. */
        @Builder.Default
        private boolean notifyOrgUsers = false;

        /**
         * Extra wording appended to the email body — "Please schedule remediation", "To
         * avoid escalation, schedule a retest or update the team". Plain text; it is
         * escaped when rendered.
         */
        private String customMessage;

        public boolean isEnabledFor(EmailNotificationAudience audience) {
            return switch (audience) {
                case ASSESSORS -> notifyAssessors;
                case STAKEHOLDERS -> notifyStakeholders;
                case APP_OWNER -> notifyAppOwner;
                case MENTIONED_USERS -> includeMentionedUsers;
                case ORG_USERS -> notifyOrgUsers;
                // Always on, and never consulted: the resolver adds the owner without asking.
                // Present so this switch stays exhaustive over the enum.
                case REMEDIATION_OWNER -> true;
            };
        }

        /**
         * True when at least one audience is switched on, so a send is worth building.
         *
         * <p>{@code @JsonIgnore} because this type is persisted as JSON and Jackson treats
         * an {@code isX()} method as a property: without it the derived value was written
         * into the column and then failed to read back as an unknown field.
         */
        @JsonIgnore
        public boolean isAnyAudienceEnabled() {
            return notifyAssessors || notifyStakeholders || notifyAppOwner
                    || includeMentionedUsers || notifyOrgUsers;
        }
    }

    /** Settings for a key, never null — an unconfigured event reads as all-off. */
    public EventSettings settingsFor(String key) {
        if (events == null) return EventSettings.builder().build();
        return events.getOrDefault(key, EventSettings.builder().build());
    }
}
