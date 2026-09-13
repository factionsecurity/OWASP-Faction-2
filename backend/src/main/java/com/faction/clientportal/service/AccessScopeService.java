package com.faction.clientportal.service;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
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
 * - Membership (default): no application assignments — the user's organizations
 *   ({@link User#getOrganizationIds()}) grant FULL access to everything in each,
 *   and their sub-organizations ({@link User#getSubOrganizationIds()}) grant the
 *   applications attributed to those. See {@link OrgAccess}.
 *
 * ":org" scope (e.g. the Organization Read role) reads the same memberships:
 * read-style access to what they grant, no assignment involved.
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
    private final SubOrganizationRepository subOrganizationRepository;

    public Optional<User> currentUser(Authentication authentication) {
        if (authentication == null) return Optional.empty();
        return userRepository.findByUsername(authentication.getName());
    }

    /**
     * What a user's organization memberships grant: the organizations they belong to (everything
     * in each), and the applications their sub-organizations grant (only those). Access to an
     * application is the union — see {@link #permits(Application)}.
     */
    public record OrgAccess(Set<String> orgIds, Set<String> subOrgIds, Set<String> subOrgAppIds) {
        public static OrgAccess none() { return new OrgAccess(Set.of(), Set.of(), Set.of()); }
        public boolean isEmpty() { return orgIds.isEmpty() && subOrgIds.isEmpty(); }
        public boolean permitsOrg(String organizationId) {
            return organizationId != null && orgIds.contains(organizationId);
        }
        public boolean permits(Application app) {
            return app != null && (permitsOrg(app.getOrganizationId())
                    || (app.getSubOrganizationId() != null && subOrgIds.contains(app.getSubOrganizationId())));
        }
    }

    public OrgAccess resolveOrgAccess(User user) {
        if (user == null) return OrgAccess.none();
        Set<String> orgIds = user.getOrganizationIds() == null ? Set.of() : Set.copyOf(user.getOrganizationIds());
        Set<String> subOrgIds = user.getSubOrganizationIds() == null ? Set.of() : Set.copyOf(user.getSubOrganizationIds());
        Set<String> subOrgAppIds = subOrgIds.isEmpty() ? Set.of()
                : subOrgIds.stream()
                        .flatMap(id -> applicationRepository.findBySubOrganizationId(id).stream())
                        .map(Application::getId)
                        .collect(Collectors.toSet());
        return new OrgAccess(orgIds, subOrgIds, subOrgAppIds);
    }

    /** The caller's memberships; {@link OrgAccess#none()} for an unknown or missing user. */
    public OrgAccess resolveOrgAccess(Authentication authentication) {
        return currentUser(authentication).map(this::resolveOrgAccess).orElse(OrgAccess.none());
    }

    /**
     * Organizations whose record the user may open: their own, plus the parents of their
     * sub-organizations. Opening the parent does not grant its other applications — that is still
     * decided per application by {@link OrgAccess#permits(Application)}.
     */
    public Set<String> visibleOrganizationIds(OrgAccess access) {
        Set<String> ids = new HashSet<>(access.orgIds());
        if (!access.subOrgIds().isEmpty()) {
            subOrganizationRepository.findAllById(access.subOrgIds()).forEach(s -> ids.add(s.getOrganizationId()));
        }
        return ids;
    }

    /** The subset of {@code appIds} whose application belongs to one of {@code organizationIds}. */
    public Set<String> applicationIdsWithinOrganizations(Set<String> appIds, java.util.Collection<String> organizationIds) {
        if (appIds.isEmpty() || organizationIds == null || organizationIds.isEmpty()) return Set.of();
        return applicationRepository.findAllById(appIds).stream()
                .filter(a -> a.getOrganizationId() != null && organizationIds.contains(a.getOrganizationId()))
                .map(Application::getId)
                .collect(Collectors.toSet());
    }

    /** Every application id an {@link OrgAccess} grants, for callers that page in memory. */
    public Set<String> applicationIdsFor(OrgAccess access) {
        Set<String> ids = new HashSet<>(access.subOrgAppIds());
        if (!access.orgIds().isEmpty()) {
            applicationRepository.findByOrganizationIdIn(access.orgIds()).forEach(a -> ids.add(a.getId()));
        }
        return ids;
    }

    /** True when the user has application-level assignments (restricted mode). */
    public boolean isAppLevelRestricted(String userId) {
        return !applicationRepository.findByAssignedUsersUserId(userId).isEmpty();
    }

    /**
     * Application ids the user owns: their assigned applications when app-level restricted,
     * otherwise everything their organization and sub-organization memberships grant.
     */
    public Set<String> ownedApplicationIds(String userId) {
        List<Application> assigned = applicationRepository.findByAssignedUsersUserId(userId);
        if (!assigned.isEmpty()) {
            return assigned.stream().map(Application::getId).collect(Collectors.toSet());
        }
        return userRepository.findById(userId)
                .map(u -> applicationIdsFor(resolveOrgAccess(u)))
                .orElse(Set.of());
    }

    public boolean ownsApplication(String userId, Application application) {
        List<Application> assigned = applicationRepository.findByAssignedUsersUserId(userId);
        if (!assigned.isEmpty()) {
            return assigned.stream().anyMatch(a -> a.getId().equals(application.getId()));
        }
        return userRepository.findById(userId)
                .map(u -> resolveOrgAccess(u).permits(application))
                .orElse(false);
    }

    /**
     * Edit rights on the application: WRITE assignment when app-level restricted; membership
     * users have full access to the applications their memberships grant.
     */
    public boolean canWriteApplication(String userId, Application application) {
        List<Application> assigned = applicationRepository.findByAssignedUsersUserId(userId);
        if (!assigned.isEmpty()) {
            return assignment(application.getAssignedUsers(), userId)
                    .map(a -> ACCESS_WRITE.equals(a.getAccessLevel()))
                    .orElse(false);
        }
        return userRepository.findById(userId)
                .map(u -> resolveOrgAccess(u).permits(application))
                .orElse(false);
    }

    /**
     * Organizations visible to an owned-scope user: the ones they belong to and the parents of
     * their sub-organizations; none when app-level restricted (they work app-by-app).
     */
    public Set<String> ownedOrganizationIds(String userId) {
        if (isAppLevelRestricted(userId)) return Set.of();
        return userRepository.findById(userId)
                .map(u -> visibleOrganizationIds(resolveOrgAccess(u)))
                .orElse(Set.of());
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
     * A resolved assessment scope. The payload depends on {@code kind}: {@code orgIds} plus
     * {@code appIds} (the sub-organization grants) for ORG, {@code appIds} for OWNED,
     * {@code teamIds} for TEAM, {@code assessorId} for ASSIGNED.
     */
    public record AssessmentScope(
            AssessmentScopeKind kind, Set<String> orgIds, Set<String> appIds, Set<String> teamIds, String assessorId) {

        public boolean denied() { return kind == AssessmentScopeKind.DENIED; }
        public boolean unrestricted() { return kind == AssessmentScopeKind.UNRESTRICTED; }

        static AssessmentScope unrestrictedScope() {
            return new AssessmentScope(AssessmentScopeKind.UNRESTRICTED, null, null, null, null);
        }
        static AssessmentScope org(Set<String> orgIds, Set<String> appIds) {
            return new AssessmentScope(AssessmentScopeKind.ORG, orgIds, appIds, null, null);
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
                case ORG -> (orgIds != null && a.getOrganizationId() != null && orgIds.contains(a.getOrganizationId()))
                        || (appIds != null && a.getApplicationId() != null && appIds.contains(a.getApplicationId()));
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
            OrgAccess access = resolveOrgAccess(authentication);
            return access.isEmpty() ? AssessmentScope.deny() : AssessmentScope.org(access.orgIds(), access.subOrgAppIds());
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
                user -> resolveOrgAccess(user).permits(application),
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
        if (orgScoped && orgMatch.test(user)) return;
        if (ownedScoped && ownedMatch.test(user)) return;
        throw new AccessDeniedException("Access denied");
    }

    public boolean hasOwnedScope(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().endsWith(":owned"));
    }

    private Optional<AssignedUser> assignment(List<AssignedUser> assignedUsers, String userId) {
        if (assignedUsers == null) return Optional.empty();
        return assignedUsers.stream()
                .filter(a -> userId.equals(a.getUserId()))
                .findFirst();
    }
}
