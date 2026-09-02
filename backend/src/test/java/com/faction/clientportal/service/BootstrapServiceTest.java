package com.faction.clientportal.service;

import com.faction.clientportal.edition.CommunityOnly;
import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Campaign;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.CampaignRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")

class BootstrapServiceTest extends TestContainersConfig {

    @Autowired
    private BootstrapService bootstrapService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        campaignRepository.deleteAll();
    }

    @Test
    void run_WhenNoCampaignsExist_CreatesCurrentYearDefaultCampaign() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);

        List<Campaign> campaigns = campaignRepository.findAll();
        assertThat(campaigns).hasSize(1);
        Campaign campaign = campaigns.get(0);
        assertThat(campaign.getName()).isEqualTo(java.time.Year.now().getValue() + " Assessments");
        assertThat(campaign.getIsDefault()).isTrue();
    }

    @Test
    void run_WhenCampaignsExist_DoesNotSeedAnother() {
        ApplicationArguments args = mock(ApplicationArguments.class);
        campaignRepository.save(Campaign.builder().name("Existing").isDefault(false).build());

        bootstrapService.run(args);

        List<Campaign> campaigns = campaignRepository.findAll();
        assertThat(campaigns).hasSize(1);
        assertThat(campaigns.get(0).getName()).isEqualTo("Existing");
    }


    /**
     * The open source edition's entire role model.
     *
     * <p>The enterprise version of this test asserts ten roles; skipping it in the open
     * source build would leave that build asserting nothing at all about the two roles it
     * actually ships — which is the whole of its access control.
     */
    @CommunityOnly
    @Test
    void run_communityEdition_seedsOnlySuperAdminAndPentester() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);

        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(roleRepository.count())
                .as("the open source edition ships Super Admin and Pentester, and nothing else")
                .isEqualTo(2);

        Optional<Role> superAdmin = roleRepository.findByName("SuperAdmin");
        assertThat(superAdmin).isPresent();
        assertThat(superAdmin.get().getPermissions()).containsExactly("super_admin");

        Optional<Role> pentester = roleRepository.findByName("Pentester");
        assertThat(pentester).isPresent();
        // Same canonical permission set as the paid edition — the gate is on minting new
        // roles, never on what the two built-in ones can do.
        assertThat(pentester.get().getPermissions()).hasSize(27);
        assertThat(pentester.get().getPermissions())
                .contains("assessments:read:assigned", "assessments:edit:assigned",
                          "report_templates:read:all", "organizations:read:all");

        assertThat(roleRepository.findByName("Organization Read"))
                .as("external portal roles are an enterprise feature")
                .isEmpty();
    }

    @EnterpriseOnly
    @Test
    void run_WhenNoUsersExist_CreatesDefaultUsersAndRoles() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);

        assertThat(userRepository.count()).isEqualTo(2);
        // SuperAdmin, Pentester, Pentester-Team, Pentester-Assessment, Remediation-Team,
        // Remediation-All, Scheduling, Scheduling-Team, Organization Read, App Owner
        assertThat(roleRepository.count()).isEqualTo(10);

        Optional<Role> superAdminRole = roleRepository.findByName("SuperAdmin");
        assertThat(superAdminRole).isPresent();
        assertThat(superAdminRole.get().getPermissions()).containsExactly("super_admin");

        Optional<Role> pentesterRole = roleRepository.findByName("Pentester");
        assertThat(pentesterRole).isPresent();
        assertThat(pentesterRole.get().getPermissions()).hasSize(27);
        // The Report Designer is reachable on assessments:create:team, so the role needs the
        // template permissions every call on that page is gated on.
        assertThat(pentesterRole.get().getPermissions()).contains(
                "report_templates:read:all", "report_templates:create:all",
                "report_templates:edit:all", "report_templates:delete:all");
        // Assessments default to assigned-only — the team-wide tier is opt-in via Roles admin.
        assertThat(pentesterRole.get().getPermissions())
                .contains("assessments:read:assigned", "assessments:edit:assigned")
                .doesNotContain("assessments:read:team");
        // Organizations moved under Administration and pentesters manage it outright.
        assertThat(pentesterRole.get().getPermissions()).contains(
                "organizations:read:all", "organizations:create:all",
                "organizations:edit:all", "organizations:delete:all");

        Optional<User> adminUser = userRepository.findByUsername("admin");
        assertThat(adminUser).isPresent();
        assertThat(adminUser.get().getIsInternal()).isTrue();
        assertThat(adminUser.get().getRoleIds()).contains(superAdminRole.get().getId());

        Optional<User> pentestUser = userRepository.findByUsername("pentest");
        assertThat(pentestUser).isPresent();
        assertThat(pentestUser.get().getIsInternal()).isTrue();
        assertThat(pentestUser.get().getRoleIds()).contains(pentesterRole.get().getId());
    }

    @Test
    void run_WhenUsersExist_DoesNotCreateDuplicates() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);
        long initialUserCount = userRepository.count();
        long initialRoleCount = roleRepository.count();

        bootstrapService.run(args);

        assertThat(userRepository.count()).isEqualTo(initialUserCount);
        assertThat(roleRepository.count()).isEqualTo(initialRoleCount);
    }

    @Test
    void run_GrantsNewlySeededPermissionsToExistingPentesterRole() {
        ApplicationArguments args = mock(ApplicationArguments.class);
        bootstrapService.run(args);

        // Simulate an install whose Pentester role predates newer seeded
        // permissions but carries an admin-added extra.
        Role pentester = roleRepository.findByName("Pentester").orElseThrow();
        pentester.setPermissions(new ArrayList<>(List.of(
                "assessments:read:team",
                "applications:read:owned"
        )));
        roleRepository.save(pentester);

        bootstrapService.run(args);

        Role updated = roleRepository.findByName("Pentester").orElseThrow();
        assertThat(updated.getPermissions())
                .contains("apikeys:create:self", "apikeys:read:self", "apikeys:delete:self")
                .contains("vulnerabilities:read:assessment", "assessments:create:team")
                // Report Designer permissions reach installs that predate them
                .contains("report_templates:read:all", "report_templates:create:all",
                        "report_templates:edit:all", "report_templates:delete:all")
                .contains("organizations:create:all", "organizations:edit:all",
                        "organizations:delete:all")
                // admin-added extra survives the merge
                .contains("applications:read:owned")
                // …but the superseded team-wide assessment grant is revoked, so an existing
                // install tightens to the assigned-only default instead of keeping it forever.
                .doesNotContain("assessments:read:team")
                // content template authoring reaches installs that predate it
                .contains("content-templates:create", "content-templates:edit",
                        "content-templates:delete")
                // 27 seeded + 1 extra, no duplicates
                .hasSize(28);
    }

    @Test

    @EnterpriseOnly
    void run_SeedsScopedPentesterRoles() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);

        Role team = roleRepository.findByName("Pentester-Team").orElseThrow();
        assertThat(team.isExternalRole()).isFalse();
        assertThat(team.getPermissions())
                .contains("assessments:read:team", "assessments:edit:team", "assessments:create:team",
                        "vulnerabilities:read:team", "vulnerabilities:create:team",
                        "vulnerabilities:edit:team", "vulnerabilities:delete:team")
                // shared with the default role: no ":team" variant exists to narrow these to
                .contains("applications:read:all", "reporting:create", "reporting:download",
                        "apikeys:create:self", "apikeys:read:self", "apikeys:delete:self")
                // Peer review of the team's work, enforced by PeerReviewService#sharesTeam; submitting
                // goes through ":create:assessment", which the service limits to the assessment's
                // own assessors.
                .contains("peerreview:read:team", "peerreview:edit:team", "peerreview:create:assessment")
                .doesNotContain("peerreview:read:all", "peerreview:edit:all", "peerreview:create:all")
                // administration surfaces stay with the default Pentester role
                .doesNotContain("organizations:create:all", "organizations:edit:all",
                        "organizations:delete:all", "report_templates:read:all")
                .doesNotContain("assessments:read:assigned", "assessments:read:all");

        Role assessment = roleRepository.findByName("Pentester-Assessment").orElseThrow();
        assertThat(assessment.isExternalRole()).isFalse();
        assertThat(assessment.getPermissions())
                .contains("assessments:read:assigned", "assessments:edit:assigned",
                        "vulnerabilities:read:assessment", "vulnerabilities:create:assessment",
                        "vulnerabilities:edit:assessment", "vulnerabilities:delete:assessment")
                .contains("applications:read:all", "peerreview:create:assessment")
                // assessment scope cannot schedule new work, nor widen past its own assessments
                .doesNotContain("assessments:create:team", "assessments:read:team",
                        "vulnerabilities:read:team", "report_templates:read:all",
                        "organizations:create:all")
                // no org-wide peer review, and no team-wide review queue either
                .doesNotContain("peerreview:read:all", "peerreview:edit:all", "peerreview:create:all",
                        "peerreview:read:team", "peerreview:edit:team");
    }

    @Test

    @EnterpriseOnly
    void run_SeedsRemediationRoles() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);

        Role team = roleRepository.findByName("Remediation-Team").orElseThrow();
        assertThat(team.isExternalRole()).isFalse();
        assertThat(team.getPermissions())
                .contains("vulnerabilities:read:team", "vulnerabilities:create:team",
                        "vulnerabilities:edit:team", "vulnerabilities:delete:team")
                // Reaching a single finding runs through checkAssessmentAccess, so the assessment
                // read grant is what makes the queue's rows openable rather than 403.
                .contains("assessments:read:team")
                // Filters on the remediation queue; neither endpoint has a narrower tier.
                .contains("applications:read:all", "organizations:read:all")
                // Remediation tracks findings; it does not edit the assessment around them.
                .doesNotContain("assessments:edit:team", "assessments:create:team",
                        "assessments:read:all", "vulnerabilities:read:all");

        Role all = roleRepository.findByName("Remediation-All").orElseThrow();
        assertThat(all.isExternalRole()).isFalse();
        assertThat(all.getPermissions())
                .contains("assessments:read:all", "vulnerabilities:read:all",
                        "vulnerabilities:create:all", "vulnerabilities:edit:all",
                        "vulnerabilities:delete:all")
                .contains("applications:read:all", "organizations:read:all")
                .doesNotContain("assessments:edit:all", "assessments:create:all",
                        "vulnerabilities:read:team");
    }

    @Test

    @EnterpriseOnly
    void run_SeedsSchedulingRoles() {
        ApplicationArguments args = mock(ApplicationArguments.class);

        bootstrapService.run(args);

        Role all = roleRepository.findByName("Scheduling").orElseThrow();
        assertThat(all.isExternalRole()).isFalse();
        assertThat(all.getPermissions())
                .contains("assessments:read:all", "assessments:create:all", "assessments:edit:all",
                        "assessments:delete:all")
                .contains("users:read:all")
                .contains("applications:read:all", "applications:create:all", "applications:edit:all")
                .contains("organizations:read:all", "organizations:create:all", "organizations:edit:all")
                .contains("campaigns:read:all", "campaigns:create:all", "campaigns:edit:all")
                .contains("checklist:create", "checklist:edit")
                // the scheduling form's template picker reads templates; authoring stays elsewhere
                .contains("report_templates:read:all")
                .doesNotContain("report_templates:create:all", "report_templates:edit:all",
                        "report_templates:delete:all")
                // scheduling books work, it does not run it — and deletes only assessments
                .doesNotContain("vulnerabilities:read:all", "applications:delete:all",
                        "organizations:delete:all", "users:edit:all");

        Role team = roleRepository.findByName("Scheduling-Team").orElseThrow();
        assertThat(team.isExternalRole()).isFalse();
        assertThat(team.getPermissions())
                .contains("assessments:read:team", "assessments:create:team", "assessments:edit:team",
                        "assessments:delete:team")
                .contains("users:read:team")
                // no team tier exists for these, so the team role still books against the whole catalog
                .contains("applications:create:all", "organizations:create:all", "campaigns:create:all")
                .contains("checklist:create", "checklist:edit", "report_templates:read:all")
                .doesNotContain("assessments:read:all", "assessments:create:all",
                        "assessments:delete:all", "users:read:all");
    }

    @Test

    @EnterpriseOnly
    void run_SeedsScopedPentesterRolesOnAnExistingInstall() {
        ApplicationArguments args = mock(ApplicationArguments.class);
        // An install that predates the scoped roles: users and the default roles already exist,
        // so the empty-database seed never runs.
        bootstrapService.run(args);
        roleRepository.delete(roleRepository.findByName("Pentester-Team").orElseThrow());
        roleRepository.delete(roleRepository.findByName("Pentester-Assessment").orElseThrow());
        roleRepository.delete(roleRepository.findByName("Remediation-Team").orElseThrow());
        roleRepository.delete(roleRepository.findByName("Remediation-All").orElseThrow());
        roleRepository.delete(roleRepository.findByName("Scheduling").orElseThrow());
        roleRepository.delete(roleRepository.findByName("Scheduling-Team").orElseThrow());

        bootstrapService.run(args);

        assertThat(roleRepository.findByName("Pentester-Team")).isPresent();
        assertThat(roleRepository.findByName("Pentester-Assessment")).isPresent();
        assertThat(roleRepository.findByName("Remediation-Team")).isPresent();
        assertThat(roleRepository.findByName("Remediation-All")).isPresent();
        assertThat(roleRepository.findByName("Scheduling")).isPresent();
        assertThat(roleRepository.findByName("Scheduling-Team")).isPresent();
    }

    @Test

    @EnterpriseOnly
    void run_GrantsNewlySeededPermissionsToScopedPentesterRolesWithoutDroppingAdminExtras() {
        ApplicationArguments args = mock(ApplicationArguments.class);
        bootstrapService.run(args);

        Role team = roleRepository.findByName("Pentester-Team").orElseThrow();
        team.setPermissions(new ArrayList<>(List.of(
                "assessments:read:team",
                "applications:read:owned" // admin-added extra
        )));
        roleRepository.save(team);

        bootstrapService.run(args);

        assertThat(roleRepository.findByName("Pentester-Team").orElseThrow().getPermissions())
                .contains("vulnerabilities:read:team", "apikeys:read:self", "peerreview:read:team")
                .contains("applications:read:owned");
    }

    @Test

    @EnterpriseOnly
    void run_RevokesOrgWidePeerReviewFromScopedPentesterRoles() {
        ApplicationArguments args = mock(ApplicationArguments.class);
        bootstrapService.run(args);

        // An install seeded before the team-scoped peer-review strings: the role carries the
        // org-wide grants, which would let a team-scoped pentester act on every team's reviews.
        Role team = roleRepository.findByName("Pentester-Team").orElseThrow();
        List<String> withOrgWide = new ArrayList<>(team.getPermissions());
        withOrgWide.addAll(List.of("peerreview:read:all", "peerreview:edit:all", "peerreview:create:all"));
        team.setPermissions(withOrgWide);
        roleRepository.save(team);

        bootstrapService.run(args);

        assertThat(roleRepository.findByName("Pentester-Team").orElseThrow().getPermissions())
                .doesNotContain("peerreview:read:all", "peerreview:edit:all", "peerreview:create:all")
                .contains("peerreview:read:team", "peerreview:edit:team", "peerreview:create:assessment");
    }
}
