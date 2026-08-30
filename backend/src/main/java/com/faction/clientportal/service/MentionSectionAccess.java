package com.faction.clientportal.service;

import com.faction.clientportal.model.MentionTargetType;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which sections of the mentions feed a caller may see.
 *
 * <p>A mention is delivered to whoever was named, which is not the same as being allowed
 * to open what it points at — an app owner can be @mentioned in an assessment note they
 * have no access to. Each section therefore requires at least one privilege on the
 * resource behind it, and rows outside those sections are dropped from the list, the
 * unread count, and the clear-the-feed delete alike. Hiding them in the browser alone
 * would leave a badge counting rows the page never shows, which can never reach zero.
 *
 * <p>Rows with no target survive every filter: they are pre-context notifications whose
 * subject is unknown, they are the recipient's own, and the page shows them under
 * "Other" — so they stay clearable.
 */
public record MentionSectionAccess(Set<MentionTargetType> visible) {

    private static final Set<MentionTargetType> EVERYTHING =
            Set.copyOf(EnumSet.allOf(MentionTargetType.class));

    /** For callers whose access is not being narrowed (super admins, internal pushes). */
    public static final MentionSectionAccess ALL = new MentionSectionAccess(EVERYTHING);

    public MentionSectionAccess {
        visible = visible == null ? Set.of() : Set.copyOf(visible);
    }

    public boolean isAll() {
        return visible.containsAll(EVERYTHING);
    }

    public boolean isEmpty() {
        return visible.isEmpty();
    }

    /**
     * Resolves the sections from a caller's granted authorities: any privilege on the
     * resource — read, edit, create, whatever tier — opens that section.
     */
    public static MentionSectionAccess of(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) return new MentionSectionAccess(Set.of());

        Set<String> granted = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());

        if (granted.contains("super_admin")) return ALL;

        Set<MentionTargetType> visible = EnumSet.noneOf(MentionTargetType.class);
        if (hasAnyOn(granted, "applications")) visible.add(MentionTargetType.APPLICATION);
        if (hasAnyOn(granted, "vulnerabilities")) visible.add(MentionTargetType.VULNERABILITY);
        // Notes live on assessments, so assessment privileges are what gate that section.
        if (hasAnyOn(granted, "assessments")) visible.add(MentionTargetType.NOTEBOOK);
        return new MentionSectionAccess(visible);
    }

    private static boolean hasAnyOn(Set<String> granted, String resource) {
        String prefix = resource + ":";
        return granted.stream().anyMatch(a -> a.startsWith(prefix));
    }
}
