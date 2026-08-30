package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.AssessmentDto;
import com.faction.clientportal.dto.UpdateAssessmentRequest;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three assessment visibility tiers a pentester role can be given, enforced consistently across
 * the list, the single-assessment fetch, the nav-badge summary and the write path:
 * {@code assessments:read:assigned} (the default — only assessments you are an assessor on),
 * {@code :read:team} (your teams' assessments) and {@code :read:all} (everything).
 *
 * <p>Before this, {@code :team} and {@code :assigned} were unenforced and every internal user saw
 * every assessment, so these are the regression tests for that hole.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentAccessScopeTest extends TestContainersConfig {

    @Autowired private AssessmentService assessmentService;
    @Autowired private AccessScopeService accessScopeService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    private String orgId;
    private String appId;
    private String alphaTeamId;
    private String betaTeamId;
    private String webTypeId;
    private String mobileTypeId;

    private User alice;   // Alpha team
    private User bob;     // Alpha team
    private User carol;   // Beta team

    private String aliceOnly;   // Alpha team, Alice assessing
    private String bobOnly;     // Alpha team, Bob assessing
    private String betaOnly;    // Beta team, Carol assessing

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        reportTemplateRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        teamRepository.deleteAll();

        orgId = organizationRepository.save(
                Organization.builder().name("Acme").description("d").build()).getId();
        appId = applicationRepository.save(
                Application.builder().name("Payments API").organizationId(orgId).build()).getId();

        assessmentTypeRepository.deleteAll();
        webTypeId = assessmentTypeRepository.save(com.faction.clientportal.model.AssessmentType.builder()
                .name("Web").description("d").active(true).build()).getId();
        mobileTypeId = assessmentTypeRepository.save(com.faction.clientportal.model.AssessmentType.builder()
                .name("Mobile").description("d").active(true).build()).getId();

        alphaTeamId = teamRepository.save(Team.builder().name("Alpha").description("d").build()).getId();
        betaTeamId = teamRepository.save(Team.builder().name("Beta").description("d").build()).getId();

        alice = user("alice", alphaTeamId);
        bob = user("bob", alphaTeamId);
        carol = user("carol", betaTeamId);

        aliceOnly = assessment("Alice's Assessment", alphaTeamId, alice.getId());
        bobOnly = assessment("Bob's Assessment", alphaTeamId, bob.getId());
        betaOnly = assessment("Beta Assessment", betaTeamId, carol.getId());
    }

    // ── Tier: assigned (the default) ────────────────────────────────────────────

    @Test
    void assignedScope_seesOnlyItsOwnAssessments() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());

        assertThat(names(search(auth))).containsExactly("Alice's Assessment");
    }

    @Test
    void assignedScope_cannotFetchAnotherPentestersAssessment() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());

        assertThat(assessmentService.getAssessment(aliceOnly, auth)).isNotNull();
        // 404 rather than 403 so ids can't be probed.
        assertThatThrownBy(() -> assessmentService.getAssessment(bobOnly, auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assignedScope_coversAssessmentsListedViaTheLegacySingleAssessorField() {
        Assessment legacy = assessmentRepository.save(Assessment.builder()
                .name("Legacy").applicationId(appId).organizationId(orgId).assessmentTypeId(webTypeId)
                .status("IN_PROGRESS").teamId(alphaTeamId)
                .assessorId(alice.getId()).assessorIds(new ArrayList<>())
                .createdAt(LocalDateTime.now()).build());

        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());
        assertThat(names(search(auth))).contains("Legacy");
        assertThat(assessmentService.getAssessment(legacy.getId(), auth)).isNotNull();
    }

    @Test
    void assignedScope_childResourceGuardBlocksUnassignedAssessments() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());
        Assessment mine = assessmentRepository.findById(aliceOnly).orElseThrow();
        Assessment theirs = assessmentRepository.findById(bobOnly).orElseThrow();

        // checkAssessmentAccess is what vulnerabilities, comments, retests and report
        // downloads call — the restriction has to reach them too, not just the list.
        accessScopeService.checkAssessmentAccess(auth, mine);
        assertThatThrownBy(() -> accessScopeService.checkAssessmentAccess(auth, theirs))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Tier: team ──────────────────────────────────────────────────────────────

    @Test
    void teamScope_seesEveryAssessmentOfItsOwnTeamsOnly() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_TEAM.getPermission());

        assertThat(names(search(auth)))
                .containsExactlyInAnyOrder("Alice's Assessment", "Bob's Assessment");
        assertThat(assessmentService.getAssessment(bobOnly, auth)).isNotNull();
        assertThatThrownBy(() -> assessmentService.getAssessment(betaOnly, auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void teamScope_withNoTeamsSeesNothing() {
        User loner = user("loner", null);
        var auth = auth(loner, Permission.ASSESSMENTS_READ_TEAM.getPermission());

        assertThat(search(auth)).isEmpty();
    }

    // ── Tier: all ───────────────────────────────────────────────────────────────

    @Test
    void readAllScope_seesEverything() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ALL.getPermission());

        assertThat(names(search(auth))).containsExactlyInAnyOrder(
                "Alice's Assessment", "Bob's Assessment", "Beta Assessment");
    }

    @Test
    void superAdminSeesEverything() {
        var auth = auth(alice, RequiresPermissionAuthorizationManager.SUPER_ADMIN);

        assertThat(names(search(auth))).hasSize(3);
    }

    @Test
    void noAssessmentReadAuthority_seesNothing() {
        // Previously an internal user with no assessment scope fell through every check and saw
        // every assessment. Failing closed is the point of this change.
        var auth = auth(alice, "applications:read:all");

        assertThat(search(auth)).isEmpty();
        assertThatThrownBy(() -> assessmentService.getAssessment(aliceOnly, auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── The badge count agrees with the list ────────────────────────────────────

    @Test
    void summaryCountsExactlyWhatTheListShows() {
        assertThat(assessmentService.assessmentSummary(
                auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission())).getTotal()).isEqualTo(1);
        assertThat(assessmentService.assessmentSummary(
                auth(alice, Permission.ASSESSMENTS_READ_TEAM.getPermission())).getTotal()).isEqualTo(2);
        assertThat(assessmentService.assessmentSummary(
                auth(alice, Permission.ASSESSMENTS_READ_ALL.getPermission())).getTotal()).isEqualTo(3);
        assertThat(assessmentService.assessmentSummary(
                auth(alice, "applications:read:all")).getTotal()).isZero();
    }

    // ── Writes ──────────────────────────────────────────────────────────────────

    @Test
    void editAssignedScope_cannotEditAnotherPentestersAssessment() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission(),
                Permission.ASSESSMENTS_EDIT_ASSIGNED.getPermission());

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setName("Renamed");

        assertThat(assessmentService.updateAssessment(aliceOnly, request, alice.getId(), auth)).isNotNull();
        assertThatThrownBy(() -> assessmentService.updateAssessment(bobOnly, request, alice.getId(), auth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void seeingTheTeamDoesNotConferEditingIt() {
        // The common setup: read the whole team's work, edit only your own.
        var auth = auth(alice, Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                Permission.ASSESSMENTS_EDIT_ASSIGNED.getPermission());

        assertThat(assessmentService.getAssessment(bobOnly, auth)).isNotNull();

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setName("Renamed");
        assertThatThrownBy(() -> assessmentService.updateAssessment(bobOnly, request, alice.getId(), auth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void editTeamScope_canEditAnyOfItsTeamsAssessments() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                Permission.ASSESSMENTS_EDIT_TEAM.getPermission());

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setName("Renamed by teammate");

        assertThat(assessmentService.updateAssessment(bobOnly, request, alice.getId(), auth)).isNotNull();
        assertThatThrownBy(() -> assessmentService.updateAssessment(betaOnly, request, alice.getId(), auth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void internalCallersWithoutAuthenticationAreNotScoped() {
        // Peer review and the schedulers update assessments with no Authentication — they must
        // keep working, or completing a review would fail for everyone.
        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setName("System rename");

        assertThat(assessmentService.updateAssessment(betaOnly, request, "system")).isNotNull();
    }

    // ── Reopen window ───────────────────────────────────────────────────────────

    @Test
    void aRecentlyCompletedAssessmentCanBeReopened() {
        completeAssessment(aliceOnly, LocalDateTime.now().minusDays(5));

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setStatus("IN_PROGRESS");
        var dto = assessmentService.updateAssessment(aliceOnly, request, alice.getId());

        assertThat(dto.getStatus()).isEqualTo("IN_PROGRESS");
        // Cleared, so finalizing again starts a fresh window rather than reusing the old stamp.
        assertThat(assessmentRepository.findById(aliceOnly).orElseThrow().getCompletedDate()).isNull();
    }

    @Test
    void anAssessmentCompletedBeyondTheWindowCannotBeReopened() {
        completeAssessment(aliceOnly, LocalDateTime.now().minusDays(31));

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setStatus("IN_PROGRESS");

        assertThatThrownBy(() -> assessmentService.updateAssessment(aliceOnly, request, alice.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can no longer be reopened");
        assertThat(assessmentRepository.findById(aliceOnly).orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void aCompletedAssessmentWithNoCompletionDateCannotBeReopened() {
        // Predates the completedDate stamp — treated as outside the window rather than
        // reopenable forever.
        Assessment a = assessmentRepository.findById(aliceOnly).orElseThrow();
        a.setStatus("COMPLETED");
        a.setCompletedDate(null);
        assessmentRepository.save(a);

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setStatus("IN_PROGRESS");

        assertThatThrownBy(() -> assessmentService.updateAssessment(aliceOnly, request, alice.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can no longer be reopened");
    }

    @Test
    void editingACompletedAssessmentWithoutChangingStatusIsUnaffected() {
        completeAssessment(aliceOnly, LocalDateTime.now().minusDays(90));

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setName("Renamed long after completion");

        // The window governs reopening, not every edit — an unrelated change must not trip it.
        assertThat(assessmentService.updateAssessment(aliceOnly, request, alice.getId())).isNotNull();
    }

    @Test
    void reopenableAssessmentsStayInTheQueue_lapsedOnesDropOut() {
        completeAssessment(aliceOnly, LocalDateTime.now().minusDays(5));    // still reopenable
        completeAssessment(bobOnly, LocalDateTime.now().minusDays(31));     // lapsed

        // The default list hides completed work; one of these is still reopenable, so it stays
        // visible to whoever could reopen it without switching on "show completed".
        var open = assessmentService.searchAssessmentsAdvanced(
                null, null, null, null, null, null, null, null, null, null, null,
                null, false, null, null, PAGE,
                auth(alice, RequiresPermissionAuthorizationManager.SUPER_ADMIN)).getContent();

        assertThat(names(open)).contains("Alice's Assessment", "Beta Assessment");
        assertThat(names(open)).doesNotContain("Bob's Assessment");
    }

    /** Put an assessment into the completed state, stamped as finishing at {@code when}. */
    private void completeAssessment(String assessmentId, LocalDateTime when) {
        Assessment a = assessmentRepository.findById(assessmentId).orElseThrow();
        a.setStatus("COMPLETED");
        a.setCompletedDate(when);
        assessmentRepository.save(a);
    }

    // ── Assignable assessors ────────────────────────────────────────────────────

    @Test
    void assignableAssessors_areLimitedToTheAssessmentsTeam() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());

        // aliceOnly belongs to Alpha, so Carol (Beta) must not be offered.
        assertThat(assessmentService.getAssignableAssessors(aliceOnly, auth))
                .extracting(com.faction.clientportal.dto.AssignableUserDto::id)
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    @Test
    void assignableAssessors_fallBackToEveryInternalUserWhenTheAssessmentHasNoTeam() {
        String teamless = assessment("Teamless", null, alice.getId());
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());

        assertThat(assessmentService.getAssignableAssessors(teamless, auth))
                .extracting(com.faction.clientportal.dto.AssignableUserDto::id)
                .contains(alice.getId(), bob.getId(), carol.getId());
    }

    @Test
    void assignableAssessors_excludeExternalDisabledAndDeletedUsers() {
        userRepository.save(User.builder()
                .username("portal").firstName("Port").lastName("Al").email("p@test.com")
                .password("x").loginOption(LoginOption.NATIVE).organizationId(orgId)
                .teamIds(new ArrayList<>(List.of(alphaTeamId)))
                .isInternal(false).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
        userRepository.save(User.builder()
                .username("retired").firstName("Re").lastName("Tired").email("r@test.com")
                .password("x").loginOption(LoginOption.NATIVE).organizationId(orgId)
                .teamIds(new ArrayList<>(List.of(alphaTeamId)))
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now())
                .disabledAt(LocalDateTime.now()).build());

        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());
        assertThat(assessmentService.getAssignableAssessors(aliceOnly, auth))
                .extracting(com.faction.clientportal.dto.AssignableUserDto::id)
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    @Test
    void assignableAssessors_requireAccessToTheAssessment() {
        // The endpoint is gated on assessment access, not users:read — so it must not become a
        // backdoor into the user directory from an assessment you can't see.
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());

        assertThatThrownBy(() -> assessmentService.getAssignableAssessors(betaOnly, auth))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignableAssessors_carryADisplayName() {
        var auth = auth(alice, Permission.ASSESSMENTS_READ_ASSIGNED.getPermission());

        assertThat(assessmentService.getAssignableAssessors(aliceOnly, auth))
                .extracting(com.faction.clientportal.dto.AssignableUserDto::displayName)
                .allSatisfy(name -> assertThat(name).isNotBlank())
                .contains("T U"); // firstName lastName, not the raw id
    }

    // ── Assessment type reassignment ────────────────────────────────────────────

    @Test
    void updateCanReassignTheAssessmentType() {
        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setAssessmentTypeId(mobileTypeId);

        var dto = assessmentService.updateAssessment(aliceOnly, request, "system");

        assertThat(dto.getAssessmentTypeId()).isEqualTo(mobileTypeId);
    }

    @Test
    void reassigningTheTypeIsRejectedWhenTheReportTemplateBelongsToAnotherType() {
        // The template carries the field definitions, so letting the two drift apart would leave
        // the assessment with fields that belong to the old type.
        var template = reportTemplateRepository.save(com.faction.clientportal.model.ReportTemplate.builder()
                .name("Type-1 Template").assessmentTypeId(webTypeId).css("").version(1).active(true)
                .userDefinedFields(new ArrayList<>()).sections(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        Assessment a = assessmentRepository.findById(aliceOnly).orElseThrow();
        a.setReportTemplateId(template.getId());
        assessmentRepository.save(a);

        UpdateAssessmentRequest request = new UpdateAssessmentRequest();
        request.setAssessmentTypeId(mobileTypeId);

        assertThatThrownBy(() -> assessmentService.updateAssessment(aliceOnly, request, "system"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Report template assessment type does not match");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<AssessmentDto> search(Authentication auth) {
        return assessmentService.searchAssessmentsAdvanced(
                null, null, null, null, null, null, null, null, null, null, null,
                null, true, null, null, PAGE, auth).getContent();
    }

    private List<String> names(List<AssessmentDto> dtos) {
        return dtos.stream().map(AssessmentDto::getName).toList();
    }

    private String assessment(String name, String teamId, String assessorId) {
        return assessmentRepository.save(Assessment.builder()
                .name(name)
                .applicationId(appId)
                .organizationId(orgId)
                .assessmentTypeId(webTypeId)
                .status("IN_PROGRESS")
                .teamId(teamId)
                .assessorIds(new ArrayList<>(List.of(assessorId)))
                .createdAt(LocalDateTime.now())
                .build()).getId();
    }

    private User user(String username, String teamId) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).organizationId(orgId)
                .teamIds(teamId == null ? new ArrayList<>() : new ArrayList<>(List.of(teamId)))
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private Authentication auth(User user, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(user.getUsername(), null, granted);
    }
}
