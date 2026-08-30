package com.faction.clientportal.model;

/**
 * How an {@link ApiKey}'s effective authorities are resolved at authentication time.
 *
 * <p>The two user-key scopes are <em>predicates over the owner's live permissions</em>, evaluated
 * fresh on every request — nothing is snapshotted, so a user key can never go stale (owner loses a
 * permission → the key loses it immediately; owner gains one → a {@code READ_WRITE} key gains it,
 * and a {@code READ_ONLY} key gains it if it is a read action). {@code CUSTOM} is the opposite: a
 * stored, admin-assigned list with no owner to track.
 *
 * <p>Type/scope invariant (enforced in {@code ApiKeyService}, the only writer): {@code USER} keys
 * are {@code READ_WRITE} or {@code READ_ONLY}; {@code SYSTEM} keys are always {@code CUSTOM}.
 * {@code CUSTOM} is never permitted on user keys — a self-service key with an arbitrary stored
 * permission list is the privilege-escalation surface this model exists to remove.
 */
public enum ApiKeyScope {

    /** The owner's live permissions, unfiltered. Default for user keys. */
    READ_WRITE,

    /**
     * The owner's live permissions filtered to read actions (see
     * {@link Permission#isReadAction(String)}). A {@code super_admin} owner is first expanded to
     * the full {@link Permission} universe, then filtered — so the key holds every known read
     * permission but never the {@code super_admin} wildcard itself. Deliberate consequence:
     * endpoints gated {@code super_admin}-only refuse an admin's read-only key.
     */
    READ_ONLY,

    /**
     * Exactly the permissions stored on the key ({@code ApiKey.permissions}) — no derivation.
     * System (service-account) keys only; assignment is {@code super_admin}-gated at the API.
     */
    CUSTOM
}
