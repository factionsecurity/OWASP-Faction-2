package com.faction.clientportal.security;

import com.faction.clientportal.model.Permission;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Authorizes {@link RequiresPermission}-annotated methods: access is granted
 * when the caller holds any of the listed permissions. The {@code super_admin}
 * authority is implied globally here, so individual endpoints never need to
 * (and must not) list it.
 */
public final class RequiresPermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    public static final String SUPER_ADMIN = "super_admin";

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, MethodInvocation invocation) {
        RequiresPermission annotation = resolveAnnotation(invocation);
        if (annotation == null || annotation.value().length == 0) {
            // Pointcut guarantees the annotation exists; deny defensively if not
            return new AuthorizationDecision(false);
        }
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        Set<String> granted = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (granted.contains(SUPER_ADMIN)) {
            return new AuthorizationDecision(true);
        }
        for (Permission permission : annotation.value()) {
            if (granted.contains(permission.getPermission())) {
                return new AuthorizationDecision(true);
            }
        }
        return new AuthorizationDecision(false);
    }

    private RequiresPermission resolveAnnotation(MethodInvocation invocation) {
        RequiresPermission annotation =
                AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), RequiresPermission.class);
        if (annotation != null) {
            return annotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                invocation.getMethod().getDeclaringClass(), RequiresPermission.class);
    }
}
