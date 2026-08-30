package com.faction.clientportal.dto;

import com.faction.clientportal.model.EmailNotificationAudience;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The whole notification routing table, already expanded for the UI.
 *
 * <p>The per-stage "vulnerability closed" events are expanded here rather than in the
 * frontend so the page never has to know how a settings key is composed, and a stage
 * renamed in Assessment Config shows its new name without a second request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailNotificationConfigDto {

    private boolean enabled;

    /** True when SMTP itself is configured and on; false means nothing will send whatever is set here. */
    private boolean smtpConfigured;

    private int pastDueRepeatCount;

    private int pastDueRepeatIntervalDays;

    private List<EventDto> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventDto {

        /** Settings key, e.g. {@code ASSESSMENT_CREATED} or {@code VULNERABILITY_CLOSED:staging}. */
        private String key;

        /** The underlying event name, without any stage suffix. */
        private String event;

        private String label;

        private String description;

        /** Which audience switches this event actually offers. */
        private List<EmailNotificationAudience> audiences;

        private boolean notifyAssessors;
        private boolean notifyStakeholders;
        private boolean notifyAppOwner;
        private boolean includeMentionedUsers;
        private boolean notifyOrgUsers;

        private String customMessage;

        /** True for the per-remediation-stage "vulnerability closed" entries. */
        private boolean perStage;

        /** Remediation stage id, for per-stage entries only. */
        private String stageId;
    }
}
