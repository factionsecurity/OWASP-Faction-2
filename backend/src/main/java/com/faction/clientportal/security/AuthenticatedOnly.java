package com.faction.clientportal.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly declares that an endpoint requires only a valid login, no
 * specific permission. The actual gate is SecurityConfig's
 * {@code anyRequest().authenticated()}; this marker exists so the
 * EndpointAuthorizationArchitectureTest can tell "deliberately open to any
 * authenticated user" apart from "someone forgot to add authorization".
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedOnly {
}
