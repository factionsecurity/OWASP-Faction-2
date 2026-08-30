package com.faction.clientportal.security;

import com.faction.clientportal.model.Permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Grants access when the caller holds ANY of the listed permissions.
 * {@code super_admin} is always implied — never list it explicitly.
 *
 * <p>This is the type-safe replacement for string-based
 * {@code @PreAuthorize("hasAnyAuthority(...)")}: referencing the
 * {@link Permission} enum means typos fail at compile time and the
 * assignable-permission catalog can't drift from what endpoints enforce.
 * Enforced by {@link RequiresPermissionAuthorizationManager}; endpoint
 * coverage is verified by the EndpointAuthorizationArchitectureTest.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    Permission[] value();
}
