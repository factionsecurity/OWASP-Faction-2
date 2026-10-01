package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RetestDto;
import com.faction.clientportal.exception.BusinessRuleException;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Re-assign to me": a tester who can see a retest assigned to someone else takes it over. The
 * retest moves to them alone, and only while it is still open and they may edit the assessment.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestAssignToMeTest extends TestContainersConfig {

    @Autowired private RetestService retestService;
    @Autowired private RetestRepository retestRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private UserRepository userRepository;

    private User me;
    private User other;
    private String assessmentId;

    @BeforeEach
    void setUp() {
        me = user("me-" + UUID.randomUUID());
        other = user("other-" + UUID.randomUUID());
        assessmentId = assessmentRepository.save(Assessment.builder()
                .name("A").assessmentTypeId("t").status("Testing")
                .assessorIds(new ArrayList<>(List.of(other.getId())))
                .createdAt(LocalDateTime.now()).build()).getId();
    }

    @Test
    void takesTheRetestOverFromItsCurrentAssignee() {
        Retest r = retest("SCHEDULED");

        RetestDto dto = retestService.assignToMe(r.getId(), auth(me, Permission.ASSESSMENTS_EDIT_ALL.getPermission()));

        assertThat(dto.getAssignedAssessorIds()).containsExactly(me.getId());
        assertThat(retestRepository.findById(r.getId()).orElseThrow().getAssignedAssessorIds())
                .containsExactly(me.getId());
    }

    @Test
    void undoingHandsTheRetestBackToWhoeverHadIt() {
        // The page's Undo sends the previous assessors back through the ordinary update.
        Retest r = retest("SCHEDULED");
        retestService.assignToMe(r.getId(), auth(me, Permission.ASSESSMENTS_EDIT_ALL.getPermission()));
        com.faction.clientportal.dto.UpdateRetestRequest undo = new com.faction.clientportal.dto.UpdateRetestRequest();
        undo.setAssignedAssessorIds(List.of(other.getId()));

        RetestDto dto = retestService.update(r.getId(), undo, me.getUsername());

        assertThat(dto.getAssignedAssessorIds()).containsExactly(other.getId());
        assertThat(dto.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void worksWhileInProgressToo() {
        Retest r = retest("IN_PROGRESS");

        RetestDto dto = retestService.assignToMe(r.getId(), auth(me, Permission.ASSESSMENTS_EDIT_ALL.getPermission()));

        assertThat(dto.getAssignedAssessorIds()).containsExactly(me.getId());
    }

    @Test
    void aFinishedRetestCannotBeTakenOver() {
        Retest r = retest("PASSED");

        assertThatThrownBy(() -> retestService.assignToMe(r.getId(),
                auth(me, Permission.ASSESSMENTS_EDIT_ALL.getPermission())))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(retestRepository.findById(r.getId()).orElseThrow().getAssignedAssessorIds())
                .containsExactly(other.getId());
    }

    @Test
    void aTesterWhoCannotEditTheAssessmentCannotTakeItsRetest() {
        // Assigned-only scope, and not an assessor on this assessment.
        Retest r = retest("SCHEDULED");

        assertThatThrownBy(() -> retestService.assignToMe(r.getId(),
                auth(me, Permission.ASSESSMENTS_EDIT_ASSIGNED.getPermission())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(retestRepository.findById(r.getId()).orElseThrow().getAssignedAssessorIds())
                .containsExactly(other.getId());
    }

    private Retest retest(String status) {
        return retestRepository.save(Retest.builder()
                .vulnerabilityId("v-" + UUID.randomUUID()).assessmentId(assessmentId).status(status)
                .assignedAssessorIds(new ArrayList<>(List.of(other.getId())))
                .scheduledStartDate(LocalDateTime.now()).scheduledEndDate(LocalDateTime.now().plusDays(2))
                .createdBy("system").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private User user(String username) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).teamIds(List.of())
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private Authentication auth(User user, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(user.getUsername(), null, granted);
    }
}
