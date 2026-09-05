package com.faction.clientportal.model;

/**
 * Who an inline image belongs to, which is what decides who may load it.
 *
 * <p>An explicit column rather than inferring "library" from a null {@code assessmentId}: the two
 * scopes have materially different visibility, and a bug that left the assessment id unset would
 * silently publish a client screenshot to every user of the platform.
 */
public enum InlineImageScope {

    /** Owned by one assessment. Readable only by callers who may read that assessment. */
    ASSESSMENT,

    /**
     * Owned by a template — content templates and default vulnerabilities — and reusable across
     * assessments. Readable by any authenticated user, because there is no assessment to scope it
     * to and the finding it ends up in may be read by a customer.
     *
     * <p>The consequence is worth stating plainly: anything pasted into a template is visible to
     * everyone on the platform. Templates are boilerplate, not client evidence.
     */
    LIBRARY
}
