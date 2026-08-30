package com.faction.clientportal.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The events that can trigger an outbound notification email, and the audiences each one
 * can be routed to.
 *
 * <p>Audiences are declared per event rather than offered uniformly because they are not
 * all meaningful everywhere: nobody is "mentioned" on an assessment being created, and
 * assessors are told about retests and SLA breaches through their own assignment
 * notifications rather than this routing table.
 *
 * <p>{@link #VULNERABILITY_CLOSED} is special: it is configured once per remediation
 * stage, so its settings are keyed {@code VULNERABILITY_CLOSED:<stageId>} (see
 * {@link #stageKey}). That keeps "closed in Development" and "closed in Production"
 * independently switchable without a code change every time someone adds a stage.
 */
public enum EmailNotificationEvent {

    ASSESSMENT_CREATED("Assessment created",
            "When a new assessment is scheduled.",
            EmailNotificationAudience.ASSESSORS,
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.ORG_USERS),

    ASSESSMENT_CHANGED("Assessment changed",
            "When an assessment's details, dates or status are edited.",
            EmailNotificationAudience.ASSESSORS,
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.ORG_USERS),

    ASSESSMENT_COMPLETED("Assessment completed",
            "When an assessment is finalized.",
            EmailNotificationAudience.ASSESSORS,
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.ORG_USERS),

    RETEST_SCHEDULED("Retest scheduled",
            "When a retest is scheduled for a finding.",
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.MENTIONED_USERS,
            EmailNotificationAudience.ORG_USERS),

    RETEST_COMPLETED("Retest passed or failed",
            "When a retest is completed, whether it passed or failed.",
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.MENTIONED_USERS,
            EmailNotificationAudience.ORG_USERS),

    VULNERABILITY_WARNING("Vulnerability approaching its due date",
            "A single digest of every finding that has entered its SLA warning window.",
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.MENTIONED_USERS,
            EmailNotificationAudience.ORG_USERS),

    VULNERABILITY_PAST_DUE("Vulnerability past its due date",
            "A single digest of every finding that has breached its SLA. Can be repeated.",
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.MENTIONED_USERS,
            EmailNotificationAudience.ORG_USERS),

    VULNERABILITY_CLOSED("Vulnerability closed",
            "When a finding is marked complete in a remediation stage.",
            EmailNotificationAudience.STAKEHOLDERS,
            EmailNotificationAudience.APP_OWNER,
            EmailNotificationAudience.MENTIONED_USERS,
            EmailNotificationAudience.ORG_USERS);

    /** Separates the event from its remediation stage id in a settings key. */
    public static final String KEY_SEPARATOR = ":";

    private final String label;
    private final String description;
    private final Set<EmailNotificationAudience> audiences;

    EmailNotificationEvent(String label, String description, EmailNotificationAudience... audiences) {
        this.label = label;
        this.description = description;
        this.audiences = new LinkedHashSet<>(Arrays.asList(audiences));
    }

    public String label() { return label; }

    public String description() { return description; }

    public Set<EmailNotificationAudience> audiences() { return audiences; }

    public boolean supports(EmailNotificationAudience audience) {
        return audiences.contains(audience);
    }

    /** True when this event is configured once per remediation stage rather than once overall. */
    public boolean isPerStage() {
        return this == VULNERABILITY_CLOSED;
    }

    /** The settings key for this event — {@code VULNERABILITY_CLOSED:staging} for a staged event. */
    public String key(String stageId) {
        if (!isPerStage() || stageId == null || stageId.isBlank()) return name();
        return name() + KEY_SEPARATOR + stageId;
    }

    public String key() {
        return key(null);
    }

    /** Resolves a settings key back to its event, ignoring any stage suffix. */
    public static EmailNotificationEvent fromKey(String key) {
        if (key == null || key.isBlank()) return null;
        String name = key.split(KEY_SEPARATOR, 2)[0];
        return Arrays.stream(values())
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** The stage id embedded in a settings key, or null for a non-staged key. */
    public static String stageIdFromKey(String key) {
        if (key == null) return null;
        String[] parts = key.split(KEY_SEPARATOR, 2);
        return parts.length == 2 ? parts[1] : null;
    }

    public static List<EmailNotificationEvent> all() {
        return Arrays.asList(values());
    }
}
