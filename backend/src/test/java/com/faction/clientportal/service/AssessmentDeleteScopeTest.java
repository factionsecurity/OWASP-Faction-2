package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting an assessment is scoped, not just gated.
 *
 * <p>The endpoint gate accepts {@code assessments:delete:all} and {@code :delete:team} alike and
 * cannot tell them apart, so the scope check has to happen in the service. Before it existed, the
 * team tier deleted anything — which only started to matter when a seeded role (Scheduling-Team)
 * actually carried it.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentDeleteScopeTest extends TestContainersConfig {

    @Autowired private AssessmentService assessmentService;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void deleteTeamScoped_deletesTheirOwnTeamsAssessment() {
        teamUser("scheduler", "team-a");
        String mine = assessment("Ours", "team-a");

        assessmentService.deleteAssessment(mine, "scheduler",
                auth("scheduler", Permission.ASSESSMENTS_DELETE_TEAM.getPermission()));

        assertThat(assessmentRepository.findByIdAndDeletedAtIsNull(mine)).isEmpty();
    }

    @Test
    void deleteTeamScoped_cannotDeleteAnotherTeamsAssessment() {
        teamUser("scheduler", "team-a");
        String theirs = assessment("Theirs", "team-b");
        var authentication = auth("scheduler", Permission.ASSESSMENTS_DELETE_TEAM.getPermission());

        assertThatThrownBy(() -> assessmentService.deleteAssessment(theirs, "scheduler", authentication))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(assessmentRepository.findByIdAndDeletedAtIsNull(theirs)).isPresent();
    }

    @Test
    void deleteTeamScoped_cannotDeleteAnAssessmentWithNoTeam() {
        teamUser("scheduler", "team-a");
        String orphan = assessment("Unassigned", null);
        var authentication = auth("scheduler", Permission.ASSESSMENTS_DELETE_TEAM.getPermission());

        assertThatThrownBy(() -> assessmentService.deleteAssessment(orphan, "scheduler", authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteAllScoped_deletesAnyAssessment() {
        teamUser("scheduler", "team-a");
        String theirs = assessment("Theirs", "team-b");

        assessmentService.deleteAssessment(theirs, "scheduler",
                auth("scheduler", Permission.ASSESSMENTS_DELETE_ALL.getPermission()));

        assertThat(assessmentRepository.findByIdAndDeletedAtIsNull(theirs)).isEmpty();
    }

    @Test
    void superAdmin_deletesAnyAssessment() {
        teamUser("root", "team-a");
        String theirs = assessment("Theirs", "team-b");

        assessmentService.deleteAssessment(theirs, "root",
                auth("root", RequiresPermissionAuthorizationManager.SUPER_ADMIN));

        assertThat(assessmentRepository.findByIdAndDeletedAtIsNull(theirs)).isEmpty();
    }

    @Test
    void internalCaller_withNoAuthentication_isUnscoped() {
        String any = assessment("Anything", "team-b");

        assessmentService.deleteAssessment(any, "system", null);

        assertThat(assessmentRepository.findByIdAndDeletedAtIsNull(any)).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private String assessment(String name, String teamId) {
        return assessmentRepository.save(Assessment.builder()
                .name(name).assessmentTypeId("t").status("IN_PROGRESS").teamId(teamId)
                .createdAt(LocalDateTime.now()).build()).getId();
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
