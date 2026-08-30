package com.faction.clientportal.dto;

import com.faction.clientportal.model.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * A person who can be assigned to an assessment, reduced to what a picker needs.
 *
 * <p>Deliberately minimal rather than a {@link UserDto}: the assignable-assessors endpoint is
 * gated on assessment access, not {@code users:read}, so it is reachable by roles (the built-in
 * Pentester among them) that are not allowed to read the user directory. Returning roles,
 * organization, login option or lockout state through it would hand back exactly what that
 * permission withholds.
 */
@Builder
public record AssignableUserDto(
        @Schema(description = "User id, used as the assessor id") String id,
        @Schema(description = "Name for display, falling back to the username") String displayName,
        @Schema(description = "Email, for the picker's secondary line") String email) {

    public static AssignableUserDto fromEntity(User user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return AssignableUserDto.builder()
                .id(user.getId())
                .displayName(full.isEmpty() ? user.getUsername() : full)
                .email(user.getEmail())
                .build();
    }
}
