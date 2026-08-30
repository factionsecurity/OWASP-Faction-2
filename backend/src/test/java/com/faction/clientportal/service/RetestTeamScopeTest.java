package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RetestDto;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retest list is the one vulnerability surface that isn't a query the scope resolver filters —
 * it post-filters in {@link RetestService}. A team-scoped internal caller used to fall through that
 * filter (it only narrowed external {@code :org}/{@code :owned} callers) and see every team's work.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestTeamScopeTest extends TestContainersConfig {

    @Autowired private RetestService retestService;
    @Autowired private RetestRepository retestRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private com.faction.clientportal.repository.VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void teamScopedUser_seesOnlyTheirTeamsRetests() {
        teamUser("tester", "team-a");
        retestOn(assessment("Ours", "team-a"), "ourFinding");
        retestOn(assessment("Theirs", "team-b"), "theirFinding");
        retestOn(assessment("Unassigned", null), "noTeamFinding");

        var names = names(retestService.getAll(false, "tester",
                auth("tester", Permission.ASSESSMENTS_READ_TEAM.getPermission(),
                        Permission.VULNERABILITIES_READ_TEAM.getPermission())));

        assertThat(names).containsExactly("ourFinding");
    }

    @Test
    void assessmentScopedUser_seesOnlyRetestsOnTheirOwnAssessments() {
        var me = userRepository.save(User.builder()
                .username("assignee").firstName("T").lastName("U").email("assignee@test.com")
                .password("x").loginOption(LoginOption.NATIVE).teamIds(List.of())
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
        var mine = assessmentRepository.save(Assessment.builder()
                .name("Mine").assessmentTypeId("t").status("IN_PROGRESS")
                .assessorIds(List.of(me.getId())).createdAt(LocalDateTime.now()).build()).getId();
        retestOn(mine, "myFinding");
        retestOn(assessment("Theirs", null), "theirFinding");

        var names = names(retestService.getAll(false, "assignee",
                auth("assignee", Permission.VULNERABILITIES_READ_ASSESSMENT.getPermission(),
                        Permission.ASSESSMENTS_READ_ASSIGNED.getPermission())));

        assertThat(names).containsExactly("myFinding");
    }

    @Test
    void unscopedInternalUser_stillSeesEverything() {
        teamUser("admin", "team-a");
        retestOn(assessment("Ours", "team-a"), "ourFinding");
        retestOn(assessment("Theirs", "team-b"), "theirFinding");

        var names = names(retestService.getAll(false, "admin",
                auth("admin", Permission.ASSESSMENTS_READ_ALL.getPermission(),
                        Permission.VULNERABILITIES_READ_ALL.getPermission())));

        assertThat(names).containsExactlyInAnyOrder("ourFinding", "theirFinding");
    }

    @Test
    void superAdmin_seesEverything() {
        teamUser("root", "team-a");
        retestOn(assessment("Ours", "team-a"), "ourFinding");
        retestOn(assessment("Theirs", "team-b"), "theirFinding");

        var names = names(retestService.getAll(false, "root",
                auth("root", RequiresPermissionAuthorizationManager.SUPER_ADMIN)));

        assertThat(names).containsExactlyInAnyOrder("ourFinding", "theirFinding");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<String> names(List<RetestDto> retests) {
        return retests.stream().map(RetestDto::getVulnerabilityName).toList();
    }

    private String assessment(String name, String teamId) {
        return assessmentRepository.save(Assessment.builder()
                .name(name).assessmentTypeId("t").status("IN_PROGRESS").teamId(teamId)
                .createdAt(LocalDateTime.now()).build()).getId();
    }

    private void retestOn(String assessmentId, String vulnName) {
        var vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .name(vulnName).assessmentId(assessmentId).severity(VulnerabilitySeverity.HIGH).order(0)
                .status("Open").openedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        retestRepository.save(Retest.builder()
                .vulnerabilityId(vuln.getId()).assessmentId(assessmentId).status("SCHEDULED")
                .createdBy("system").lastUpdatedBy("system")
                .scheduledStartDate(LocalDateTime.now()).scheduledEndDate(LocalDateTime.now().plusDays(3))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private void teamUser(String username, String... teamIds) {
        userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).teamIds(List.of(teamIds))
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private Authentication auth(String username, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
