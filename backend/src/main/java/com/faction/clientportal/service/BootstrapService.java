package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.edition.EditionPolicy;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.VulnerabilityCategory;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.repository.VulnerabilityCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapService implements ApplicationRunner {

    private final UserService userService;
    private final RoleService roleService;
    private final RegionConfigService regionConfigService;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final VulnerabilityCategoryRepository vulnerabilityCategoryRepository;
    private final CampaignRepository campaignRepository;
    private final EditionPolicy editionPolicy;

    @Override
    public void run(ApplicationArguments args) {
        if (userService.count() == 0) {
            log.info("No users found. Initializing default users and roles...");
            createDefaultRoles();
            createDefaultUsers();
            log.info("Default users and roles created successfully.");
        } else {
            log.info("Users already exist. Skipping user bootstrap.");
        }

        migrateRolePermissions();
        ensurePentesterPermissions();

        // The open source edition ships Super Admin and Pentester and nothing else — the
        // seeders below mint additional roles, which is exactly what CUSTOM_ROLES gates.
        // The two core roles are still maintained above, so a downgraded install keeps
        // working; it simply stops growing new roles.
        if (editionPolicy.enabled(Feature.CUSTOM_ROLES)) {
            ensurePentesterScopeRoles();
            ensureRemediationRoles();
            ensureSchedulingRoles();
        }
        if (editionPolicy.enabled(Feature.EXTERNAL_OWNERS)) {
            ensureExternalRoles();
        }

        regionConfigService.ensureDefaults();

        if (assessmentTypeRepository.count() == 0) {
            log.info("No assessment types found. Initializing default assessment types...");
            createDefaultAssessmentTypes();
            log.info("Default assessment types created successfully.");
        } else {
            log.info("Assessment types already exist. Skipping assessment type bootstrap.");
        }

        if (vulnerabilityCategoryRepository.countByDeletedAtIsNull() == 0) {
            log.info("No vulnerability categories found. Initializing OWASP Top 10 categories...");
            createOwaspTop10Categories();
            log.info("OWASP Top 10 vulnerability categories created successfully.");
        } else {
            log.info("Vulnerability categories already exist. Skipping vulnerability category bootstrap.");
        }

        if (campaignRepository.count() == 0) {
            createDefaultCampaign();
        }
    }

    /** Fresh installs get a "<current year> Assessments" campaign as the default. */
    private void createDefaultCampaign() {
        LocalDateTime now = LocalDateTime.now();
        String name = now.getYear() + " Assessments";
        campaignRepository.save(Campaign.builder()
                .name(name)
                .isDefault(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("No campaigns found. Created default campaign '{}'.", name);
    }

    /** Canonical permission set for the built-in Pentester role. */
    private static final List<String> PENTESTER_PERMISSIONS = List.of(
            // Assessments are assigned-only by default: a pentester sees and edits the assessments
            // they are an assessor on, nothing else. Widen a role to assessments:read:team (their
            // team's work) or assessments:read:all (everything) in Roles admin — those tiers are
            // enforced by AccessScopeService#resolveAssessmentScope.
            Permission.ASSESSMENTS_READ_ASSIGNED.getPermission(),
            Permission.ASSESSMENTS_EDIT_ASSIGNED.getPermission(),
            Permission.ASSESSMENTS_CREATE_TEAM.getPermission(),
            Permission.VULNERABILITIES_READ_ASSESSMENT.getPermission(),
            Permission.VULNERABILITIES_CREATE_ASSESSMENT.getPermission(),
            Permission.VULNERABILITIES_EDIT_ASSESSMENT.getPermission(),
            Permission.VULNERABILITIES_DELETE_ASSESSMENT.getPermission(),
            Permission.APPLICATIONS_READ_ALL.getPermission(),
            // Full organization management — the Organizations page lives under Administration
            // and pentesters run it, so they get the same create/edit/delete the page exposes.
            Permission.ORGANIZATIONS_READ_ALL.getPermission(),
            Permission.ORGANIZATIONS_CREATE_ALL.getPermission(),
            Permission.ORGANIZATIONS_EDIT_ALL.getPermission(),
            Permission.ORGANIZATIONS_DELETE_ALL.getPermission(),
            // Peer review gates honour only the ":all" scope — the ":team" variants are
            // defined but inert until PeerReviewService filters by team.
            Permission.PEERREVIEW_READ_ALL.getPermission(),
            Permission.PEERREVIEW_EDIT_ALL.getPermission(),
            Permission.PEERREVIEW_CREATE_ALL.getPermission(),
            Permission.REPORTING_CREATE.getPermission(),
            Permission.REPORTING_DOWNLOAD.getPermission(),
            // Report Designer. The page is already reachable on assessments:create:team, but every
            // call it makes is gated on report_templates:*:all — without these it opens onto a 403.
            // All four are needed: the designer lists, opens, creates, saves, uploads and deletes.
            Permission.REPORT_TEMPLATES_READ_ALL.getPermission(),
            Permission.REPORT_TEMPLATES_CREATE_ALL.getPermission(),
            Permission.REPORT_TEMPLATES_EDIT_ALL.getPermission(),
            Permission.REPORT_TEMPLATES_DELETE_ALL.getPermission(),
            Permission.APIKEYS_CREATE_SELF.getPermission(),
            Permission.APIKEYS_READ_SELF.getPermission(),
            Permission.APIKEYS_DELETE_SELF.getPermission()
    );

    /**
     * Permissions revoked from the built-in Pentester role on upgrade.
     *
     * <p>{@code assessments:read:team} used to be indistinguishable from "read everything" — no
     * code enforced the tier — so it was seeded by default. Now that the tiers are enforced, the
     * default is assigned-only, and an existing install would otherwise keep the wider grant
     * forever (the merge below only adds). This is the one place seeding takes something away;
     * re-grant it in Roles admin for teams that want the team-wide view.
     */
    private static final List<String> PENTESTER_REVOKED_PERMISSIONS = List.of(
            Permission.ASSESSMENTS_READ_TEAM.getPermission()
    );

    /**
     * The Pentester seed above only applies on an empty database, so installs that
     * predate a permission added to it later (e.g. apikeys:*:self) never receive it.
     * Additively grants any missing seeded permissions on startup and revokes the ones in
     * {@link #PENTESTER_REVOKED_PERMISSIONS}; other permissions an admin has added on top are
     * preserved. Idempotent.
     */
    private void ensurePentesterPermissions() {
        roleService.findByName("Pentester").ifPresent(role -> {
            List<String> current = role.getPermissions() == null ? List.of() : role.getPermissions();
            List<String> missing = PENTESTER_PERMISSIONS.stream()
                    .filter(p -> !current.contains(p))
                    .toList();
            List<String> revoked = PENTESTER_REVOKED_PERMISSIONS.stream()
                    .filter(current::contains)
                    .toList();
            if (missing.isEmpty() && revoked.isEmpty()) return;

            List<String> merged = new java.util.ArrayList<>(current);
            merged.addAll(missing);
            merged.removeAll(revoked);
            role.setPermissions(merged);
            roleService.updateRole(role);
            if (!missing.isEmpty()) {
                log.info("Pentester role: granted {} newly seeded permissions: {}", missing.size(), missing);
            }
            if (!revoked.isEmpty()) {
                log.info("Pentester role: revoked {} permission(s) now superseded by the assigned-only "
                        + "assessment default: {}", revoked.size(), revoked);
            }
        });
    }

    /**
     * Permissions the scoped Pentester roles share with the default one, regardless of tier.
     *
     * <p>Deliberately excludes the administration surfaces the default Pentester carries
     * (organizations:*:all, report_templates:*:all): a scoped pentester works inside the
     * assessments they are given, and does not run Organizations admin or the Report Designer.
     * applications:read:all stays because every assessment view resolves its application, and
     * no ":team" application permission exists to narrow it to.
     *
     * <p>Peer review is scoped per role rather than shared: the team role gets the ":team" read/edit
     * strings (PeerReviewService#sharesTeam enforces them), and both roles submit through
     * ":create:assessment", which the service allows only for an assessment's own assessors.
     */
    private static final List<String> PENTESTER_SHARED_PERMISSIONS = List.of(
            Permission.APPLICATIONS_READ_ALL.getPermission(),
            Permission.PEERREVIEW_CREATE_ASSESSMENT.getPermission(),
            Permission.REPORTING_CREATE.getPermission(),
            Permission.REPORTING_DOWNLOAD.getPermission(),
            Permission.APIKEYS_CREATE_SELF.getPermission(),
            Permission.APIKEYS_READ_SELF.getPermission(),
            Permission.APIKEYS_DELETE_SELF.getPermission()
    );

    /**
     * Team tier: the pentester sees and edits everything their team is on, and can schedule new
     * team work. Enforced by AccessScopeService#resolveAssessmentScope / #resolveAssessmentEditScope,
     * which resolve ":team" against the user's teamIds.
     */
    private static final List<String> PENTESTER_TEAM_PERMISSIONS = Stream.concat(
            Stream.of(
                    Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                    Permission.ASSESSMENTS_EDIT_TEAM.getPermission(),
                    Permission.ASSESSMENTS_CREATE_TEAM.getPermission(),
                    Permission.VULNERABILITIES_READ_TEAM.getPermission(),
                    Permission.VULNERABILITIES_CREATE_TEAM.getPermission(),
                    Permission.VULNERABILITIES_EDIT_TEAM.getPermission(),
                    Permission.VULNERABILITIES_DELETE_TEAM.getPermission(),
                    // Peer review of the team's work: the queue and every review action filter to
                    // assessments sharing a team with the caller.
                    Permission.PEERREVIEW_READ_TEAM.getPermission(),
                    Permission.PEERREVIEW_EDIT_TEAM.getPermission()),
            PENTESTER_SHARED_PERMISSIONS.stream()).toList();

    /**
     * Assessment tier: the pentester works only inside the assessments they are an assessor on.
     * Assessments have no ":assessment" scope — ":assigned" is that tier for them — and there is
     * no create grant, so this role cannot schedule new work.
     */
    private static final List<String> PENTESTER_ASSESSMENT_PERMISSIONS = Stream.concat(
            Stream.of(
                    Permission.ASSESSMENTS_READ_ASSIGNED.getPermission(),
                    Permission.ASSESSMENTS_EDIT_ASSIGNED.getPermission(),
                    Permission.VULNERABILITIES_READ_ASSESSMENT.getPermission(),
                    Permission.VULNERABILITIES_CREATE_ASSESSMENT.getPermission(),
                    Permission.VULNERABILITIES_EDIT_ASSESSMENT.getPermission(),
                    Permission.VULNERABILITIES_DELETE_ASSESSMENT.getPermission()),
            PENTESTER_SHARED_PERMISSIONS.stream()).toList();

    /**
     * Seeds the two narrower Pentester roles alongside the default one, so an admin can assign a
     * scope tier without hand-building the permission list. "Pentester" itself is unchanged and
     * stays the default.
     *
     * <p>Runs on every startup, not just on an empty database — an existing install gets the roles
     * on upgrade. Additive, like {@link #ensurePentesterPermissions()}: permissions an admin has
     * added on top are preserved, and only missing seeded ones are granted.
     */
    private void ensurePentesterScopeRoles() {
        ensureInternalRole("Pentester-Team",
                "Penetration Tester scoped to their team: reads and edits the assessments, "
                        + "vulnerabilities, and peer reviews their team is assigned to, and can "
                        + "schedule team work",
                PENTESTER_TEAM_PERMISSIONS, PENTESTER_SCOPE_REVOKED_PERMISSIONS);
        ensureInternalRole("Pentester-Assessment",
                "Penetration Tester scoped to assigned assessments: reads and edits only the "
                        + "assessments they are an assessor on, and their vulnerabilities",
                PENTESTER_ASSESSMENT_PERMISSIONS, PENTESTER_SCOPE_REVOKED_PERMISSIONS);
    }

    /**
     * Revoked from the scoped Pentester roles on startup. They were first seeded with the
     * org-wide peer-review grants, on the belief that the ":team" variants were unenforced; they
     * are enforced, and leaving ":all" in place would let a team-scoped pentester read and act on
     * every team's reviews — exactly what the role exists to prevent.
     */
    private static final List<String> PENTESTER_SCOPE_REVOKED_PERMISSIONS = List.of(
            Permission.PEERREVIEW_READ_ALL.getPermission(),
            Permission.PEERREVIEW_EDIT_ALL.getPermission(),
            Permission.PEERREVIEW_CREATE_ALL.getPermission()
    );

    /**
     * Creates an internal (non-portal) role if absent, otherwise additively grants any seeded
     * permission it is missing and strips the ones in {@code revoked}. Anything else an admin has
     * added on top survives. Idempotent.
     */
    private void ensureInternalRole(String name, String description, List<String> permissions,
                                    List<String> revoked) {
        roleService.findByName(name).ifPresentOrElse(
                existing -> {
                    List<String> current = existing.getPermissions() == null
                            ? List.of() : existing.getPermissions();
                    List<String> missing = permissions.stream()
                            .filter(p -> !current.contains(p))
                            .toList();
                    List<String> toRevoke = revoked.stream().filter(current::contains).toList();
                    if (missing.isEmpty() && toRevoke.isEmpty()) return;
                    List<String> merged = new java.util.ArrayList<>(current);
                    merged.addAll(missing);
                    merged.removeAll(toRevoke);
                    existing.setPermissions(merged);
                    roleService.updateRole(existing);
                    if (!missing.isEmpty()) {
                        log.info("'{}' role: granted {} newly seeded permissions: {}",
                                name, missing.size(), missing);
                    }
                    if (!toRevoke.isEmpty()) {
                        log.info("'{}' role: revoked {} permission(s) superseded by a narrower scope: {}",
                                name, toRevoke.size(), toRevoke);
                    }
                },
                () -> {
                    roleService.createRole(Role.builder()
                            .name(name)
                            .description(description)
                            .permissions(permissions)
                            .externalRole(false)
                            .build());
                    log.info("Created '{}' role", name);
                }
        );
    }

    /**
     * Support permissions both Remediation roles need to render their pages, neither of which has a
     * narrower tier to use: the remediation queue's application and organization filters are fed by
     * endpoints gated on {@code applications:read:all} / {@code organizations:read:all}.
     */
    private static final List<String> REMEDIATION_SHARED_PERMISSIONS = List.of(
            Permission.APPLICATIONS_READ_ALL.getPermission(),
            Permission.ORGANIZATIONS_READ_ALL.getPermission()
    );

    /**
     * Remediation work at team scope: read, comment on, create, edit, and delete vulnerabilities
     * and their retests for the assessments belonging to the user's teams.
     *
     * <p>Two of the listed capabilities have no permission string of their own, which is why this
     * list looks shorter than the role description:
     * <ul>
     *   <li><b>Commenting</b> is gated on the vulnerability <em>read</em> family — the
     *       {@code vulnerabilities:comment:*} strings exist only in {@code :org}/{@code :owned}
     *       form, for portal users. {@code vulnerabilities:read:team} carries it.</li>
     *   <li><b>Retests</b> reuse the vulnerability verbs at the same tier: RetestController gates
     *       create/read/edit/delete on {@code vulnerabilities:{create,read,edit,delete}:*}.</li>
     * </ul>
     *
     * <p>{@code assessments:read:team} is not optional. Every single-vulnerability operation runs
     * through VulnerabilityService#enforceOrgScope → AccessScopeService#checkAssessmentAccess, and
     * a caller with no assessment read grant resolves to DENIED — the queue would list findings
     * that 403 the moment one is opened. Read-only on purpose: remediation tracks findings, it
     * does not edit the assessment around them.
     */
    private static final List<String> REMEDIATION_TEAM_PERMISSIONS = Stream.concat(
            Stream.of(
                    Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                    Permission.VULNERABILITIES_READ_TEAM.getPermission(),
                    Permission.VULNERABILITIES_CREATE_TEAM.getPermission(),
                    Permission.VULNERABILITIES_EDIT_TEAM.getPermission(),
                    Permission.VULNERABILITIES_DELETE_TEAM.getPermission()),
            REMEDIATION_SHARED_PERMISSIONS.stream()).toList();

    /** The same remediation work, unscoped: every assessment's findings and retests. */
    private static final List<String> REMEDIATION_ALL_PERMISSIONS = Stream.concat(
            Stream.of(
                    Permission.ASSESSMENTS_READ_ALL.getPermission(),
                    Permission.VULNERABILITIES_READ_ALL.getPermission(),
                    Permission.VULNERABILITIES_CREATE_ALL.getPermission(),
                    Permission.VULNERABILITIES_EDIT_ALL.getPermission(),
                    Permission.VULNERABILITIES_DELETE_ALL.getPermission()),
            REMEDIATION_SHARED_PERMISSIONS.stream()).toList();

    /**
     * Seeds the two Remediation roles — the remediation queue's own roles, for people who track
     * findings to closure without running assessments. Same additive, idempotent contract as the
     * Pentester roles.
     */
    private void ensureRemediationRoles() {
        ensureInternalRole("Remediation-Team",
                "Remediation for the user's teams: read, comment on, create, edit, and delete the "
                        + "vulnerabilities and retests of their teams' assessments",
                REMEDIATION_TEAM_PERMISSIONS, List.of());
        ensureInternalRole("Remediation-All",
                "Remediation across every assessment: read, comment on, create, edit, and delete "
                        + "any vulnerability and retest",
                REMEDIATION_ALL_PERMISSIONS, List.of());
    }

    /**
     * What both Scheduling roles need beyond their assessment tier. Several of these are ":all"
     * because no narrower tier exists — applications, organizations, and campaigns define only
     * org-wide variants — so a team-scoped scheduler still books work against the whole catalog.
     *
     * <p>{@code report_templates:read:all} is not an authoring grant: the scheduling form loads the
     * templates available for the chosen assessment type, and that endpoint is gated on it. Without
     * it the template picker 403s mid-form. Create/edit/delete stay with the Pentester role.
     */
    private static final List<String> SCHEDULING_SHARED_PERMISSIONS = List.of(
            Permission.APPLICATIONS_READ_ALL.getPermission(),
            Permission.APPLICATIONS_CREATE_ALL.getPermission(),
            Permission.APPLICATIONS_EDIT_ALL.getPermission(),
            Permission.ORGANIZATIONS_READ_ALL.getPermission(),
            Permission.ORGANIZATIONS_CREATE_ALL.getPermission(),
            Permission.ORGANIZATIONS_EDIT_ALL.getPermission(),
            Permission.CAMPAIGNS_READ_ALL.getPermission(),
            Permission.CAMPAIGNS_CREATE_ALL.getPermission(),
            Permission.CAMPAIGNS_EDIT_ALL.getPermission(),
            Permission.CHECKLIST_TEMPLATES_CREATE.getPermission(),
            Permission.CHECKLIST_TEMPLATES_EDIT.getPermission(),
            Permission.REPORT_TEMPLATES_READ_ALL.getPermission()
    );

    /** Scheduling across every assessment, with the org-wide user directory. */
    private static final List<String> SCHEDULING_ALL_PERMISSIONS = Stream.concat(
            Stream.of(
                    Permission.ASSESSMENTS_READ_ALL.getPermission(),
                    Permission.ASSESSMENTS_CREATE_ALL.getPermission(),
                    Permission.ASSESSMENTS_EDIT_ALL.getPermission(),
                    Permission.ASSESSMENTS_DELETE_ALL.getPermission(),
                    Permission.USERS_READ_ALL.getPermission()),
            SCHEDULING_SHARED_PERMISSIONS.stream()).toList();

    /**
     * Scheduling for the user's own teams: they book and edit their teams' assessments and see
     * only their teammates in the assessor picker ({@code users:read:team} is filtered to shared
     * teams by UserService).
     */
    private static final List<String> SCHEDULING_TEAM_PERMISSIONS = Stream.concat(
            Stream.of(
                    Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                    Permission.ASSESSMENTS_CREATE_TEAM.getPermission(),
                    Permission.ASSESSMENTS_EDIT_TEAM.getPermission(),
                    Permission.ASSESSMENTS_DELETE_TEAM.getPermission(),
                    Permission.USERS_READ_TEAM.getPermission()),
            SCHEDULING_SHARED_PERMISSIONS.stream()).toList();

    /**
     * Seeds the two Scheduling roles — for the people who book the work rather than perform it.
     * Same additive, idempotent contract as the other seeded roles.
     */
    private void ensureSchedulingRoles() {
        ensureInternalRole("Scheduling",
                "Books, edits, and deletes any assessment, maintains applications, organizations, "
                        + "campaigns, and checklist templates, and reads the full user directory",
                SCHEDULING_ALL_PERMISSIONS, List.of());
        ensureInternalRole("Scheduling-Team",
                "Books, edits, and deletes their teams' assessments, maintains applications, "
                        + "organizations, campaigns, and checklist templates, and reads their teammates",
                SCHEDULING_TEAM_PERMISSIONS, List.of());
    }

    /** Permission strings renamed during the enum reconciliation (2026-07). */
    private static final java.util.Map<String, String> RENAMED_PERMISSIONS = java.util.Map.of(
            "assessments:view:all", "assessments:read:all",
            "assessments:view:team", "assessments:read:team",
            "assessments:view:assigned", "assessments:read:assigned",
            "assessments:write:all", "assessments:edit:all",
            "assessments:write:team", "assessments:edit:team",
            "assessments:write:assigned", "assessments:edit:assigned"
    );

    /**
     * Keeps stored role documents aligned with the Permission enum: applies
     * historical renames and strips strings that are grantable nowhere (they
     * would silently do nothing). Idempotent; runs on every startup.
     */
    private void migrateRolePermissions() {
        for (Role role : roleService.findAll()) {
            List<String> original = role.getPermissions();
            if (original == null) continue;
            List<String> cleaned = original.stream()
                    .map(p -> RENAMED_PERMISSIONS.getOrDefault(p, p))
                    .distinct()
                    .filter(p -> {
                        boolean valid = "super_admin".equals(p) || Permission.fromString(p) != null;
                        if (!valid) {
                            log.warn("Role '{}': dropping unknown permission '{}'", role.getName(), p);
                        }
                        return valid;
                    })
                    .toList();
            if (!cleaned.equals(original)) {
                role.setPermissions(cleaned);
                roleService.updateRole(role);
                log.info("Role '{}': permissions migrated ({} -> {})",
                        role.getName(), original.size(), cleaned.size());
            }
        }
    }

    private void ensureExternalRoles() {
        ensureRole("Organization Read",
                "Read-only access to organization data for portal users",
                List.of(
                        "organizations:read:org",
                        "applications:read:org",
                        "vulnerabilities:read:org",
                        "assessments:read:org"
                ));

        // Owned scope: sees only applications assigned to them, either directly
        // (application-level) or via an assignment on the organization (org-level).
        // WRITE-level assignees can additionally edit the application.
        ensureRole("App Owner",
                "Application owner scoped to assigned applications: edit (WRITE assignees), "
                        + "view vulnerabilities, comment, complete surveys, schedule remediation, "
                        + "and download reports",
                List.of(
                        Permission.APPLICATIONS_READ_OWNED.getPermission(),
                        Permission.APPLICATIONS_CREATE_OWNED.getPermission(),
                        Permission.ORGANIZATIONS_READ_OWNED.getPermission(),
                        Permission.ASSESSMENTS_READ_OWNED.getPermission(),
                        Permission.VULNERABILITIES_READ_OWNED.getPermission(),
                        Permission.VULNERABILITIES_COMMENT_OWNED.getPermission(),
                        Permission.VULNERABILITIES_RETEST_OWNED.getPermission(),
                        Permission.REPORTING_DOWNLOAD_OWNED.getPermission(),
                        Permission.SURVEYS_COMPLETE.getPermission()
                ));
    }

    private void ensureRole(String name, String description, List<String> permissions) {
        roleService.findByName(name).ifPresentOrElse(
                existing -> {
                    if (!existing.getPermissions().containsAll(permissions)) {
                        existing.setPermissions(permissions);
                        roleService.updateRole(existing);
                        log.info("Updated '{}' external role permissions", name);
                    }
                },
                () -> {
                    Role role = Role.builder()
                            .name(name)
                            .description(description)
                            .permissions(permissions)
                            .externalRole(true)
                            .build();
                    roleService.createRole(role);
                    log.info("Created '{}' external role", name);
                }
        );
    }

    private void createDefaultRoles() {
        Role superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleService.createRole(superAdminRole);
        log.info("Created SuperAdmin role with ID: {}", superAdminRole.getId());

        Role pentesterRole = Role.builder()
                .name("Pentester")
                .description("Penetration Tester with assessment permissions")
                .permissions(PENTESTER_PERMISSIONS)
                .build();
        pentesterRole = roleService.createRole(pentesterRole);
        log.info("Created Pentester role with ID: {}", pentesterRole.getId());
    }

    private void createDefaultUsers() {
        Role superAdminRole = roleService.findByName("SuperAdmin")
                .orElseThrow(() -> new RuntimeException("SuperAdmin role not found"));
        Role pentesterRole = roleService.findByName("Pentester")
                .orElseThrow(() -> new RuntimeException("Pentester role not found"));

        User adminUser = User.builder()
                .username("admin")
                .email("admin@factionsecurity.com")
                .firstName("System")
                .lastName("Administrator")
                .password("admin123")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(superAdminRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        adminUser = userService.createUser(adminUser);
        log.info("Created admin user with ID: {}", adminUser.getId());

        User pentestUser = User.builder()
                .username("pentest")
                .email("pentest@factionsecurity.com")
                .firstName("Penetration")
                .lastName("Tester")
                .password("pentest123")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(pentesterRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        pentestUser = userService.createUser(pentestUser);
        log.info("Created pentest user with ID: {}", pentestUser.getId());
    }

    private void createOwaspTop10Categories() {
        List<String[]> owasp = List.of(
            new String[]{"A01 - Broken Access Control",        "Restrictions on what authenticated users can do are not properly enforced, allowing attackers to access unauthorized functionality or data."},
            new String[]{"A02 - Cryptographic Failures",       "Failures related to cryptography that often lead to exposure of sensitive data or system compromise."},
            new String[]{"A03 - Injection",                    "User-supplied data is not validated, filtered, or sanitized, enabling attackers to send hostile data to an interpreter."},
            new String[]{"A04 - Insecure Design",              "Missing or ineffective control designs that cannot be fixed by a perfect implementation alone."},
            new String[]{"A05 - Security Misconfiguration",    "Missing appropriate security hardening, insecure default configurations, or improperly configured cloud services."},
            new String[]{"A06 - Vulnerable and Outdated Components", "Components such as libraries, frameworks, and other software modules run with the same privileges as the application and may contain known vulnerabilities."},
            new String[]{"A07 - Identification and Authentication Failures", "Weaknesses in authentication and session management that allow attackers to assume other users' identities."},
            new String[]{"A08 - Software and Data Integrity Failures", "Code and infrastructure that does not protect against integrity violations, such as insecure deserialization or use of untrusted plugins."},
            new String[]{"A09 - Security Logging and Monitoring Failures", "Insufficient logging, detection, monitoring, and active response that allows attacks to go undetected and unaddressed."},
            new String[]{"A10 - Server-Side Request Forgery", "The application fetches a remote resource without validating the user-supplied URL, allowing attackers to coerce it into sending crafted requests to unexpected destinations."}
        );

        for (String[] entry : owasp) {
            VulnerabilityCategory category = VulnerabilityCategory.builder()
                    .name(entry[0])
                    .description(entry[1])
                    .createdBy("system")
                    .lastUpdatedBy("system")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            vulnerabilityCategoryRepository.save(category);
            log.info("Created vulnerability category: {}", entry[0]);
        }
    }

    private void createDefaultAssessmentTypes() {
        AssessmentType webAppPentest = AssessmentType.builder()
                .name("Web Application Pentest")
                .description("Comprehensive security testing of web applications")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        webAppPentest = assessmentTypeRepository.save(webAppPentest);
        log.info("Created Web Application Pentest assessment type with ID: {}", webAppPentest.getId());

        AssessmentType mobileAppPentest = AssessmentType.builder()
                .name("Mobile Application Pentest")
                .description("Security assessment of iOS and Android mobile applications")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        mobileAppPentest = assessmentTypeRepository.save(mobileAppPentest);
        log.info("Created Mobile Application Pentest assessment type with ID: {}", mobileAppPentest.getId());

        AssessmentType networkAssessment = AssessmentType.builder()
                .name("Network Assessment")
                .description("Network infrastructure security assessment and vulnerability testing")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        networkAssessment = assessmentTypeRepository.save(networkAssessment);
        log.info("Created Network Assessment assessment type with ID: {}", networkAssessment.getId());

        AssessmentType redTeamAssessment = AssessmentType.builder()
                .name("RedTeam Assessment")
                .description("Goal-oriented adversarial assessment simulating real-world attack scenarios")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        redTeamAssessment = assessmentTypeRepository.save(redTeamAssessment);
        log.info("Created RedTeam Assessment assessment type with ID: {}", redTeamAssessment.getId());
    }
}
