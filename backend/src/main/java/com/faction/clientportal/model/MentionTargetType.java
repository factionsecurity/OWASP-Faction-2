package com.faction.clientportal.model;

/**
 * What a mention was written on. This decides whether an emailed mention can be
 * replied to: a comment is a thread an inbound reply can be appended to, whereas an
 * assessment note is a document body — appending email to someone's note would be
 * destructive and ordering-ambiguous.
 */
public enum MentionTargetType {

    /** Application comment thread — {@code Application.comments}. Replyable. */
    APPLICATION,

    /** Vulnerability comment thread — {@code Vulnerability.comments}. Replyable. */
    VULNERABILITY,

    /** Assessment note body — {@code NotebookNode.content}. Notify-only. */
    NOTEBOOK;

    /** True when an emailed mention on this target may carry a Reply-To token. */
    public boolean supportsEmailReply() {
        return this == APPLICATION || this == VULNERABILITY;
    }

    /**
     * True when the target keeps a subscriber list, and therefore when there is something
     * for an unsubscribe link to actually remove. Notes are a document body rather than a
     * thread, so they have none — and offering "remove me" there would be a link that
     * errors when clicked.
     */
    public boolean supportsSubscribers() {
        return this == APPLICATION || this == VULNERABILITY;
    }
}
