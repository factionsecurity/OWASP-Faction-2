package com.faction.clientportal.model;

/**
 * Identifies the thing a mention was written on, so the mention email can be bound to a
 * thread a reply can join. Factory methods rather than a raw constructor because the
 * assessment id is required for vulnerabilities (their service API is assessment-scoped)
 * and meaningless for the others — a shape that is easy to get wrong positionally.
 */
public record MentionTarget(MentionTargetType type, String id, String assessmentId, String name) {

    public static MentionTarget application(String applicationId, String name) {
        return new MentionTarget(MentionTargetType.APPLICATION, applicationId, null, name);
    }

    public static MentionTarget vulnerability(String vulnerabilityId, String assessmentId, String name) {
        return new MentionTarget(MentionTargetType.VULNERABILITY, vulnerabilityId, assessmentId, name);
    }

    /** Assessment notes are notify-only — there is no thread to append a reply to. */
    public static MentionTarget notebook(String nodeId, String name) {
        return new MentionTarget(MentionTargetType.NOTEBOOK, nodeId, null, name);
    }
}
