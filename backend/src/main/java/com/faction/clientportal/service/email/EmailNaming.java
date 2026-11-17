package com.faction.clientportal.service.email;

import com.faction.clientportal.model.EmailNotificationConfig;
import com.faction.clientportal.model.MentionTargetType;

/**
 * Which names an email may carry, from the email notification settings. Some teams do not
 * want application or vulnerability names sitting in inboxes; with a switch off, an email
 * says what the item is instead of naming it.
 */
public record EmailNaming(boolean applicationNames, boolean vulnerabilityNames) {

    /**
     * Used when the settings cannot be read. Leaking a name someone switched off is the worse
     * mistake than an email that says a little less.
     */
    public static final EmailNaming HIDDEN = new EmailNaming(false, false);

    public static EmailNaming from(EmailNotificationConfig config) {
        if (config == null) return HIDDEN;
        return new EmailNaming(config.isIncludeApplicationNames(), config.isIncludeVulnerabilityNames());
    }

    /** Notes are never hidden: the switches are about application and vulnerability names. */
    public boolean shows(MentionTargetType type) {
        if (type == MentionTargetType.NOTEBOOK) return true;
        return type == MentionTargetType.VULNERABILITY ? vulnerabilityNames : applicationNames;
    }
}
