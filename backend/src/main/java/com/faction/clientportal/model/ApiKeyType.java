package com.faction.clientportal.model;

/**
 * Discriminates the two kinds of API key.
 *
 * <ul>
 *   <li>{@link #USER} — owned by a user; falls back to the owner's live permissions when the
 *       key carries no explicitly assigned permissions.</li>
 *   <li>{@link #SYSTEM} — ownerless "service account" key; carries only its explicitly assigned
 *       permissions (none by default).</li>
 * </ul>
 */
public enum ApiKeyType {
    USER,
    SYSTEM
}
