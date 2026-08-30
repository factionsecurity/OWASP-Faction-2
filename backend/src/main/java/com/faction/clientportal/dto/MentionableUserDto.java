package com.faction.clientportal.dto;

/**
 * One @mention candidate. Deliberately just the two fields the picker renders — a mention list is
 * an addressing surface, not a directory, and external users must not learn a colleague's email,
 * roles, or organization from it.
 */
public record MentionableUserDto(String username, String displayName) {}
