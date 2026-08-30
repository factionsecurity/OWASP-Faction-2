package com.faction.clientportal.dto;

import lombok.Data;

import java.util.List;

/**
 * A partial update. Every field is nullable and null means "leave it alone", so the UI can
 * save a single switch without round-tripping the whole table and clobbering a change
 * someone else made in the meantime.
 */
@Data
public class UpdateEmailNotificationConfigRequest {

    private Boolean enabled;

    private Integer pastDueRepeatCount;

    private Integer pastDueRepeatIntervalDays;

    private List<EventUpdate> events;

    @Data
    public static class EventUpdate {

        /** Settings key, e.g. {@code VULNERABILITY_PAST_DUE} or {@code VULNERABILITY_CLOSED:staging}. */
        private String key;

        private Boolean notifyAssessors;
        private Boolean notifyStakeholders;
        private Boolean notifyAppOwner;
        private Boolean includeMentionedUsers;
        private Boolean notifyOrgUsers;

        /** Empty string clears the custom wording; null leaves it unchanged. */
        private String customMessage;
    }
}
