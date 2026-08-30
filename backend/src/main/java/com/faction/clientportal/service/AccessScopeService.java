package com.faction.clientportal.service;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves external-user data scopes.
 *
 * ":owned" scope has two modes, decided by whether the user has application
 * assignments:
 * - App-level (restricted): the user appears in one or more applications'
 *   {@code assignedUsers} lists — they see ONLY those applications, with edit
 *   rights per assignment access level (WRITE edits, READ views).
 * - Org-level (default): no application assignments — the user's home
 *   organization ({@link User#getOrganizationId()}) grants FULL access to
 *   everything in that organization, including editing its applications.
 *
 * ":org" scope (e.g. the Organization Read role) is unchanged: read-style
 * access to everything in the home organization, no assignment involved.
 *
 * Internal users (authorities without an :org/:owned suffix) are not
 * restricted here — their team/assigned data checks live elsewhere.
 */
@Service
@RequiredArgsConstructor
public class AccessScopeService {

    public static final String ACCESS_WRITE = "WRITE";

    private static final String SUPER_ADMIN = RequiresPermissionAuthorizationManager.SUPER_ADMIN;

    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final AssessmentRepository assessmentRepository;

    public Optional<User> currentUser(Authentication authentication) {
        if (authentication == null) return Optional.empty();
        return userRepository.findByUsername(authentication.getName());
    }

    public String resolveOrgId(Authentication authentication) {
        return currentUser(authentication).map(User::getOrganizationId).orElse(null);
    }

    /** True when the user has application-level assignments (restricted mode). */
    public boolean isAppLevelRestricted(String userId) {
        return !applicationRepository.findByAssignedUsersUserId(userId).isEmpty();
    }

    /**
     * Application ids the user owns: their assigned applications when
     * app-level restricted, otherwise every application in their home org.
     */
    public Set<String> ownedApplicationIds(String userId) {
        List<Application> assigned = applicationRepository.findByAssignedUsersUserId(userId);
        if (!assigned.isEmpty()) {
            return assigned.stream().map(Application::getId).collect(Collectors.toSet());
        }
        return homeOrgId(userId)
                .map(orgId -> applicationRepository.findByOrganizationId(orgId).stream()
                        .map(Application::getId)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public boolean ownsApplication(String userId, Application application) {
        List<Application> assigned = applicationRepository.findByAssignedUsersUserId(userId);
        if (!assigned.isEmpty()) {
            return assigned.stream().anyMatch(a -> a.getId().equals(application.getId()));
        }
        return homeOrgId(userId)
                .map(orgId -> orgId.equals(application.getOrganizationId()))
                .orElse(false);
    }

    /**
     * Edit rights on the application: WRITE assignment when app-level
     * restricted; org-level users have full access to their org's apps.
     */
    public boolean canWriteApplication(String userId, Application application) {
        List<Application> assigned = applicationRepository.findByAssignedUsersUserId(userId);
        if (!assigned.isEmpty()) {
            return assignment(application.getAssignedUsers(), userId)
                    .map(a -> ACCESS_WRITE.equals(a.getAccessLevel()))
                    .orElse(false);
        }
        return homeOrgId(userId)
                .map(orgId -> orgId.equals(application.getOrganizationId()))
                .orElse(false);
    }

    /**
     * Organizations visible to an owned-scope user: the home organization for
     * org-level users; none when app-level restricted (they work app-by-app).
     */
    public Set<String> ownedOrganizationIds(String userId) {
        if (isAppLevelRestricted(userId)) return Set.of();
        return homeOrgId(userId).map(Set::of).orElse(Set.of());
    }

    public boolean ownsAssessment(String userId, Assessment assessment) {
        if (assessment.getApplicationId() == null) return false;
        return applicationRepository.findById(assessment.getApplicationId())
                .map(app -> ownsApplication(userId, app))
                .orElse(false);
    }

    // ── Assessment read scope ───────────────────────────────────────────────────

    /**
     * Which assessments a caller may see, resolved from their authorities. The four narrowing
     * tiers are mutually exclusive and checked in this order, so the widest permission a role
     * holds wins:
     * {@code super_admin}/{@code assessments:read:all} → everything;
     * {@code :read:org} → the caller's organization;
     * {@code :read:owned} → assessments of applications assigned to them;
     * {@code :read:team} → assessments whose {@code teamId} is one of the caller's teams;
     * {@code :read:assigned} → assessments they are listed on as an assessor.
     *
     * <p>A caller with no assessment read authority at all resolves to {@code DENIED} — they see
     * nothing. This is a deliberate change: internal users used to fall through every scope check
     * and see every assessment, which is what made {@code :read:team} and {@code :read:assigned}
     * indistinguishable from {@code :read:all}.
     */
    public enum AssessmentScopeKind { UNRESTRICTED, ORG, OWNED, TEAM, ASSIGNED, DENIED }

    /**
     * A resolved assessment scope. Exactly one payload is populated, per {@code kind}:
     * {@code orgId} for ORG, {@code appIds} for OWNED, {@code teamIds} for TEAM,
     * {@code assessorId} for ASSIGNED.
     */
    public record AssessmentScope(
            AssessmentScopeKind kind, String orgId, Set<String> appIds, Set<String> teamIds, String assessorId) {

        public boolean denied() { return kind == AssessmentScopeKind.DENIED; }
        public boolean unrestricted() { return kind == AssessmentScopeKind.UNRESTRICTED; }

        static AssessmentScope unrestrictedScope() {
            return new AssessmentScope(AssessmentScopeKind.UNRESTRICTED, null, null, null, null);
        }
        static AssessmentScope org(String orgId) {
            return new AssessmentScope(AssessmentScopeKind.ORG, orgId, null, null, null);
        }
        static AssessmentScope owned(Set<String> appIds) {
            return new AssessmentScope(AssessmentScopeKind.OWNED, null, appIds, null, null);
        }
        static AssessmentScope team(Set<String> teamIds) {
            return new AssessmentScope(AssessmentScopeKind.TEAM, null, null, teamIds, null);
        }
        static AssessmentScope assigned(String assessorId) {
            return new AssessmentScope(AssessmentScopeKind.ASSIGNED, null, null, null, assessorId);
        }
        static AssessmentScope deny() {
            return new AssessmentScope(AssessmentScopeKind.DENIED, null, null, null, null);
        }

        /**
         * Whether a single assessment falls inside this scope — the row-level counterpart of the
         * filters the list query applies. Kept here so the list and the per-assessment guard can
         * never drift apart.
         */
        public boolean permits(Assessment a) {
            return switch (kind) {
                case UNRESTRICTED -> true;
                case DENIED -> false;
                case ORG -> orgId != null && orgId.equals(a.getOrganizationId());
                case OWNED -> a.getApplicationId() != null && appIds != null && appIds.contains(a.getApplicationId());
                case TEAM -> a.getTeamId() != null && teamIds != null && teamIds.contains(a.getTeamId());
                case ASSIGNED -> assessorId != null
                        && (assessorId.equals(a.getAssessorId())
                            || (a.getAssessorIds() != null && a.getAssessorIds().contains(assessorId)));
            };
        }
    }

    public AssessmentScope resolveAssessmentScope(Authentication authentication) {
        if (authentication == null) {
            // Internal, non-HTTP callers (schedulers, bootstrap) pass no authentication.
            return AssessmentScope.unrestrictedScope();
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (authorities.contains(SUPER_ADMIN)
                || authorities.contains(Permission.ASSESSMENTS_READ_ALL.getPermission())
                // The manager dashboard is a deliberate cross-team view and is granted on its own,
                // without any assessments:read:* scope — see ManagerDashboardController.
                || authorities.contains(Permission.MANAGER_DASHBOARD_READ_ALL.getPermission())) {
            return AssessmentScope.unrestrictedScope();
        }
        if (authorities.contains(Permission.ASSESSMENTS_READ_ORG.getPermission())) {
            String orgId = resolveOrgId(authentication);
            return orgId != null ? AssessmentScope.org(orgId) : AssessmentScope.deny();
        }
        if (authorities.contains(Permission.ASSESSMENTS_READ_OWNED.getPermission())) {
            return currentUser(authentication)
                    .map(u -> AssessmentScope.owned(ownedApplicationIds(u.getId())))
                    .orElseGet(AssessmentScope::deny);
        }
        if (authorities.contains(Permission.ASSESSMENTS_READ_TEAM.getPermission())) {
            return currentUser(authentication)
                    .map(u -> {
                        var teamIds = u.getTeamIds() == null ? Set.<String>of() : Set.copyOf(u.getTeamIds());
                        return AssessmentScope.team(teamIds);
                    })
                    .orElseGet(AssessmentScope::deny);
        }
        if (authorities.contains(Permission.ASSESSMENTS_READ_ASSIGNED.getPermission())) {
            return currentUser(authentication)
                    .map(u -> AssessmentScope.assigned(u.getId()))
                    .orElseGet(AssessmentScope::deny);
        }
        return AssessmentScope.deny();
    }

    /**
     * The assessments a caller may <em>modify</em>, resolved from their edit authorities:
     * {@code super_admin}/{@code assessments:edit:all} → everything; {@code :edit:team} → their
     * teams'; {@code :edit:assigned} → the ones they're an assessor on; otherwise denied.
     *
     * <p>Separate from the read scope on purpose: the common pentester setup is "see the whole
     * team's work, edit only your own", which needs the two to differ.
     */
    public AssessmentScope resolveAssessmentEditScope(Authentication authentication) {
        if (authentication == null) {
            // Internal, non-HTTP callers (peer review, schedulers) aren't scoped.
            return AssessmentScope.unrestrictedScope();
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (authorities.contains(SUPER_ADMIN)
                || authorities.contains(Permission.ASSESSMENTS_EDIT_ALL.getPermission())) {
            return AssessmentScope.unrestrictedScope();
        }
        if (authorities.contains(Permission.ASSESSMENTS_EDIT_TEAM.getPermission())) {
            return currentUser(authentication)
                    .map(u -> AssessmentScope.team(u.getTeamIds() == null ? Set.<String>of() : Set.copyOf(u.getTeamIds())))
                    .orElseGet(AssessmentScope::deny);
        }
        if (authorities.contains(Permission.ASSESSMENTS_EDIT_ASSIGNED.getPermission())) {
            return currentUser(authentication)
                    .map(u -> AssessmentScope.assigned(u.getId()))
                    .orElseGet(AssessmentScope::deny);
        }
        return AssessmentScope.deny();
    }

    /**
     * Guard for interactions tied to an assessment (vulnerabilities, comments, retests, surveys,
     * report downloads). Enforces the caller's resolved {@link AssessmentScope} — so a pentester
     * scoped to their assigned assessments can't reach another assessment's children either.
     */
    public void checkAssessmentAccess(Authentication authentication, Assessment assessment) {
        if (!resolveAssessmentScope(authentication).permits(assessment)) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /**
     * Overload for callers holding only an assessment id — the file and inline
     * image streams, which resolve their owning assessment from the stored
     * record. A missing assessment is denied rather than reported as absent, so
     * the endpoint cannot be used to probe which assessment ids exist.
     */
    public void checkAssessmentAccess(Authentication authentication, String assessmentId) {
        Assessment assessment = assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        checkAssessmentAccess(authentication, assessment);
    }

    /**
     * The assessments a caller may <em>delete</em>. Resolved separately from the edit scope because
     * the two grants are separate: a role can hold {@code assessments:delete:team} while editing
     * only its own assigned work, or the reverse. There is no assigned tier — deletion is only
     * defined org-wide and per team.
     */
    public AssessmentScope resolveAssessmentDeleteScope(Authentication authentication) {
        if (authentication == null) {
            // Internal, non-HTTP callers (schedulers, data migrations) aren't scoped.
            return AssessmentScope.unrestrictedScope();
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (authorities.contains(SUPER_ADMIN)
                || authorities.contains(Permission.ASSESSMENTS_DELETE_ALL.getPermission())) {
            return AssessmentScope.unrestrictedScope();
        }
        if (authorities.contains(Permission.ASSESSMENTS_DELETE_TEAM.getPermission())) {
            return currentUser(authentication)
                    .map(u -> AssessmentScope.team(u.getTeamIds() == null ? Set.<String>of() : Set.copyOf(u.getTeamIds())))
                    .orElseGet(AssessmentScope::deny);
        }
        return AssessmentScope.deny();
    }

    /** Delete guard: the caller must be allowed to delete this specific assessment. */
    public void checkAssessmentDeleteAccess(Authentication authentication, Assessment assessment) {
        if (!resolveAssessmentDeleteScope(authentication).permits(assessment)) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /** Write guard: the caller must be allowed to modify this specific assessment. */
    public void checkAssessmentEditAccess(Authentication authentication, Assessment assessment) {
        if (!resolveAssessmentEditScope(authentication).permits(assessment)) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /** Guard for interactions tied to an application (comments, edits by scope). */
    public void checkApplicationAccess(Authentication authentication, Application application) {
        checkScope(authentication,
                user -> Objects.equals(user.getOrganizationId(), application.getOrganizationId()),
                user -> ownsApplication(user.getId(), application));
    }

    private void checkScope(Authentication authentication,
                            java.util.function.Predicate<User> orgMatch,
                            java.util.function.Predicate<User> ownedMatch) {
        if (authentication == null) return;
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (authorities.contains("super_admin")) return;
        boolean orgScoped = authorities.stream().anyMatch(a -> a.endsWith(":org"));
        boolean ownedScoped = authorities.stream().anyMatch(a -> a.endsWith(":owned"));
        if (!orgScoped && !ownedScoped) return; // internal user — not scoped here
        User user = currentUser(authentication)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (orgScoped && user.getOrganizationId() != null && orgMatch.test(user)) return;
        if (ownedScoped && ownedMatch.test(user)) return;
        throw new AccessDeniedException("Access denied");
    }

    public boolean hasOwnedScope(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().endsWith(":owned"));
    }

    private Optional<String> homeOrgId(String userId) {
        return userRepository.findById(userId)
                .map(User::getOrganizationId)
                .filter(Objects::nonNull);
    }

    private Optional<AssignedUser> assignment(List<AssignedUser> assignedUsers, String userId) {
        if (assignedUsers == null) return Optional.empty();
        return assignedUsers.stream()
                .filter(a -> userId.equals(a.getUserId()))
                .findFirst();
    }
}
