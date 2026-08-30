package com.faction.clientportal.security;

import com.faction.clientportal.model.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.util.SimpleMethodInvocation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequiresPermissionAuthorizationManagerTest {

    private final RequiresPermissionAuthorizationManager manager = new RequiresPermissionAuthorizationManager();

    static class MethodLevelTarget {
        @RequiresPermission({Permission.USERS_READ_ALL, Permission.USERS_READ_TEAM})
        public void guarded() {}
    }

    @RequiresPermission(Permission.ROLES_READ_ALL)
    static class ClassLevelTarget {
        public void guarded() {}
    }

    @Test
    void grantsWhenAnyListedPermissionHeld() {
        assertThat(check(MethodLevelTarget.class, "guarded", user("users:read:team")).isGranted()).isTrue();
        assertThat(check(MethodLevelTarget.class, "guarded", user("users:read:all")).isGranted()).isTrue();
    }

    @Test
    void superAdminIsAlwaysImplied() {
        assertThat(check(MethodLevelTarget.class, "guarded", user("super_admin")).isGranted()).isTrue();
        assertThat(check(ClassLevelTarget.class, "guarded", user("super_admin")).isGranted()).isTrue();
    }

    @Test
    void deniesWhenNoListedPermissionHeld() {
        assertThat(check(MethodLevelTarget.class, "guarded",
                user("organizations:read:all", "users:read")).isGranted()).isFalse();
    }

    @Test
    void deniesAnonymousUsers() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(check(MethodLevelTarget.class, "guarded", anonymous).isGranted()).isFalse();
    }

    @Test
    void classLevelAnnotationApplies() {
        assertThat(check(ClassLevelTarget.class, "guarded", user("roles:read:all")).isGranted()).isTrue();
        assertThat(check(ClassLevelTarget.class, "guarded", user("users:read:all")).isGranted()).isFalse();
    }

    private org.springframework.security.authorization.AuthorizationDecision check(
            Class<?> targetClass, String methodName, Authentication authentication) {
        try {
            Method method = targetClass.getMethod(methodName);
            return manager.check(() -> authentication,
                    new SimpleMethodInvocation(targetClass.getDeclaredConstructor().newInstance(), method));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Authentication user(String... authorities) {
        return new UsernamePasswordAuthenticationToken("tester", "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
}
