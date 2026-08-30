package com.faction.clientportal.security;

import com.faction.clientportal.model.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps endpoint authorization and the {@link Permission} catalog in sync:
 *
 * 1. Every controller handler must make an explicit authorization decision —
 *    {@code @RequiresPermission}, legacy {@code @PreAuthorize}, or
 *    {@code @AuthenticatedOnly} — unless its class serves permit-all routes.
 * 2. Every Permission enum value must be enforced by some endpoint, so the
 *    roles UI never offers permissions that do nothing. Values not yet wired
 *    up live in KNOWN_UNENFORCED; the goal is to shrink that list to empty.
 */
class EndpointAuthorizationArchitectureTest {

    // Scans from the root rather than just `controller`, so every package that contributes
    // handlers is covered. A narrower scope would let an unauthorized endpoint outside that
    // one package through while still reporting green.
    private static final String CONTROLLER_PACKAGE = "com.faction.clientportal";

    /** Controllers whose routes are permitAll in SecurityConfig (no method authorization possible). */
    private static final Set<String> PERMIT_ALL_CLASSES = Set.of(
            "AuthController",     // /api/v1/auth/** is permitAll
            // GET /api/v1/status is permitAll: it reports version and uptime to load
            // balancers and status pages, which have no credentials to present.
            "StatusController",
            "SsoAuthController",  // /api/v1/auth/** is permitAll
            // POST /api/v1/email/unsubscribe is permitAll: the unguessable token in the
            // email is the authority, and requiring a login to stop receiving mail is how
            // people mark messages as spam instead.
            "EmailUnsubscribeController"
    );

    /** Individual handlers on permitAll routes (see SecurityConfig matchers). */
    private static final Set<String> PERMIT_ALL_METHODS = Set.of(
            "InlineImageController#serve",  // GET /api/v1/inline-images/** is permitAll
            // GET /api/v1/branding and /api/v1/branding/assets/* are permitAll: the sign-in
            // page paints its logo and background before anyone has a session, so requiring
            // a token would mean the branding only appeared after login. Both disclose
            // nothing but the images a visitor is about to be shown. The write endpoints on
            // the same controller are @PreAuthorize'd and deliberately absent from this list.
            "BrandingController#getBranding",
            "BrandingController#serveAsset"
    );

    /**
     * Permission enum values not yet enforced by any endpoint. Do not add to
     * this list — wire the permission to an endpoint or delete it from the
     * enum instead.
     */
    private static final Set<String> KNOWN_UNENFORCED = Set.of();

    /** True when the paid overlay is on the classpath (set by the {@code enterprise} profile). */
    private static final boolean ENTERPRISE_BUILD =
            "enterprise".equals(System.getProperty("faction.edition.build"));

    /**
     * Permissions whose only endpoints live in the overlay.
     *
     * <p>In the open source build those controllers do not exist, so the permissions are
     * enforced nowhere — correctly. They stay in the enum because {@code Permission} is
     * core and shared by both editions; what changes is whether anything answers to them.
     * Expected-unenforced rather than skipped, so the open source build still fails if a
     * genuinely dead permission appears.
     */
    private static final Set<String> ENTERPRISE_ONLY_PERMISSIONS = Set.of(
            "sso:config:read",
            "sso:config:write");

    private static final Pattern AUTHORITY_STRING = Pattern.compile("'([^']+)'");

    @Test
    void everyEndpointMakesAnExplicitAuthorizationDecision() {
        List<String> unprotected = new ArrayList<>();
        for (Class<?> controller : findControllers()) {
            if (PERMIT_ALL_CLASSES.contains(controller.getSimpleName())) continue;
            boolean classDecided = hasAuthorizationAnnotation(controller);
            for (Method method : handlerMethods(controller)) {
                String id = controller.getSimpleName() + "#" + method.getName();
                if (!classDecided && !hasAuthorizationAnnotation(method) && !PERMIT_ALL_METHODS.contains(id)) {
                    unprotected.add(id);
                }
            }
        }
        assertThat(unprotected)
                .withFailMessage("These endpoints have no explicit authorization decision. Add " +
                        "@RequiresPermission (preferred) or @AuthenticatedOnly (login-only, deliberate):\n  %s",
                        String.join("\n  ", unprotected))
                .isEmpty();
    }

    @Test
    void everyPermissionEnumValueIsEnforcedSomewhere() {
        Set<String> enforced = new HashSet<>();
        for (Class<?> controller : findControllers()) {
            collectEnforced(controller, enforced);
            for (Method method : handlerMethods(controller)) {
                collectEnforced(method, enforced);
            }
        }

        Set<String> dead = new TreeSet<>();
        Set<String> wiredButListedAsDebt = new TreeSet<>();
        for (Permission permission : Permission.values()) {
            boolean used = enforced.contains(permission.getPermission());
            boolean debt = KNOWN_UNENFORCED.contains(permission.getPermission())
                    || (!ENTERPRISE_BUILD && ENTERPRISE_ONLY_PERMISSIONS.contains(permission.getPermission()));
            if (!used && !debt) dead.add(permission.getPermission());
            if (used && debt) wiredButListedAsDebt.add(permission.getPermission());
        }

        assertThat(dead)
                .withFailMessage("These Permission values are offered in the roles UI but enforced " +
                        "nowhere — wire them to an endpoint or remove them from the enum:\n  %s",
                        String.join("\n  ", dead))
                .isEmpty();
        assertThat(wiredButListedAsDebt)
                .withFailMessage("These permissions are now enforced — remove them from KNOWN_UNENFORCED:\n  %s",
                        String.join("\n  ", wiredButListedAsDebt))
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private boolean hasAuthorizationAnnotation(java.lang.reflect.AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(element, RequiresPermission.class)
                || AnnotatedElementUtils.hasAnnotation(element, PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(element, AuthenticatedOnly.class);
    }

    private void collectEnforced(java.lang.reflect.AnnotatedElement element, Set<String> enforced) {
        RequiresPermission rp = AnnotatedElementUtils.findMergedAnnotation(element, RequiresPermission.class);
        if (rp != null) {
            Arrays.stream(rp.value()).map(Permission::getPermission).forEach(enforced::add);
        }
        PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        if (pa != null) {
            Matcher matcher = AUTHORITY_STRING.matcher(pa.value());
            while (matcher.find()) {
                enforced.add(matcher.group(1));
            }
        }
    }

    private List<Class<?>> findControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            try {
                controllers.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(controllers).withFailMessage("No controllers found — package moved?").isNotEmpty();
        return controllers;
    }

    private List<Method> handlerMethods(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
    }
}
