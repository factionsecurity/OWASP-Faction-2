package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentWorkflowConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.PeerReview;
import com.faction.clientportal.model.PeerReviewStatus;
import com.faction.clientportal.model.Team;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentWorkflowConfigRepository;
import com.faction.clientportal.repository.PeerReviewRepository;
import com.faction.clientportal.repository.TeamRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scope enforcement for peer review. An assessment belongs to the teams of its
 * assessors; a ":team"-scoped caller only sees and works on reviews whose
 * assessment shares one of their teams. Submitting with the ":assessment" scope
 * requires being an assessor, and reviewing your own submission is blocked
 * unless enabled in Assessment Config.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PeerReviewTeamScopeTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private PeerReviewRepository peerReviewRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private AssessmentWorkflowConfigRepository configRepository;
    @Autowired private JwtService jwtService;

    private static final String READ_TEAM = "peerreview:read:team";
    private static final String EDIT_TEAM = "peerreview:edit:team";
    private static final String CREATE_ASSESSMENT = "peerreview:create:assessment";

    private Team alpha;
    private Team beta;
    private User alphaAssessor;   // assessor on the alpha assessment
    private User alphaReviewer;   // same team, did not submit
    private User betaMember;      // other team entirely
    private Assessment alphaAssessment;
    private Assessment betaAssessment;
    private PeerReview alphaReview;
    private PeerReview betaReview;

    @BeforeEach
    void setUp() {
        peerReviewRepository.deleteAll();
        assessmentRepository.deleteAll();
        userRepository.deleteAll();
        teamRepository.deleteAll();
        configRepository.deleteAll();

        alpha = teamRepository.save(Team.builder().name("Alpha").description("a")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        beta = teamRepository.save(Team.builder().name("Beta").description("b")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        alphaAssessor = saveUser("alpha-assessor", List.of(alpha.getId()));
        alphaReviewer = saveUser("alpha-reviewer", List.of(alpha.getId()));
        betaMember = saveUser("beta-member", List.of(beta.getId()));

        alphaAssessment = saveAssessment("Alpha Assessment", List.of(alphaAssessor.getId()));
        betaAssessment = saveAssessment("Beta Assessment", List.of(betaMember.getId()));

        alphaReview = saveReview(alphaAssessment.getId(), alphaAssessor.getId());
        betaReview = saveReview(betaAssessment.getId(), betaMember.getId());
    }

    // ── Queue / read scoping ────────────────────────────────────────────────

    @Test
    void queue_teamScoped_showsOnlyOwnTeamsReviews() throws Exception {
        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", token(alphaReviewer, READ_TEAM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessmentId").value(alphaAssessment.getId()));

        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", token(betaMember, READ_TEAM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessmentId").value(betaAssessment.getId()));
    }

    @Test
    void queue_readAll_showsEveryTeamsReviews() throws Exception {
        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", token(betaMember, "peerreview:read:all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void singleReview_fromAnotherTeam_isHidden() throws Exception {
        mockMvc.perform(get("/api/v1/peer-reviews/" + alphaReview.getId())
                        .header("Authorization", token(alphaReviewer, READ_TEAM)))
                .andExpect(status().isOk());

        // Not found rather than forbidden — don't advertise what they can't see
        mockMvc.perform(get("/api/v1/peer-reviews/" + betaReview.getId())
                        .header("Authorization", token(alphaReviewer, READ_TEAM)))
                .andExpect(status().isNotFound());
    }

    // ── Edit scoping ────────────────────────────────────────────────────────

    @Test
    void startReview_teamScoped_deniedForOtherTeam() throws Exception {
        mockMvc.perform(post("/api/v1/peer-reviews/" + alphaReview.getId() + "/start")
                        .header("Authorization", token(betaMember, EDIT_TEAM)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/peer-reviews/" + alphaReview.getId() + "/start")
                        .header("Authorization", token(alphaReviewer, EDIT_TEAM)))
                .andExpect(status().isOk());
    }

    // ── Self-review ─────────────────────────────────────────────────────────

    @Test
    void selfReview_isBlockedByDefault_andAllowedWhenConfigured() throws Exception {
        // The submitter shares the team (with themselves), so only the self-review rule stops them
        mockMvc.perform(post("/api/v1/peer-reviews/" + alphaReview.getId() + "/start")
                        .header("Authorization", token(alphaAssessor, EDIT_TEAM)))
                .andExpect(status().isForbidden());

        setAllowSelfPeerReview(true);

        mockMvc.perform(post("/api/v1/peer-reviews/" + alphaReview.getId() + "/start")
                        .header("Authorization", token(alphaAssessor, EDIT_TEAM)))
                .andExpect(status().isOk());
    }

    // ── Submit (create) scoping ─────────────────────────────────────────────

    @Test
    void submit_withAssessmentScope_requiresBeingAnAssessor() throws Exception {
        Assessment fresh = saveAssessment("Unsubmitted", List.of(alphaAssessor.getId()));

        // Same team, but not an assessor on it
        mockMvc.perform(post("/api/v1/assessments/" + fresh.getId() + "/peer-reviews/submit")
                        .header("Authorization", token(alphaReviewer, CREATE_ASSESSMENT)))
                .andExpect(status().isForbidden());

        // The assessor may submit their own assessment
        mockMvc.perform(post("/api/v1/assessments/" + fresh.getId() + "/peer-reviews/submit")
                        .header("Authorization", token(alphaAssessor, CREATE_ASSESSMENT)))
                .andExpect(status().isCreated());
    }

    @Test
    void submit_withCreateAll_worksForAnyAssessment() throws Exception {
        Assessment fresh = saveAssessment("Unsubmitted", List.of(alphaAssessor.getId()));

        mockMvc.perform(post("/api/v1/assessments/" + fresh.getId() + "/peer-reviews/submit")
                        .header("Authorization", token(betaMember, "peerreview:create:all")))
                .andExpect(status().isCreated());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private String token(User user, String... authorities) {
        return "Bearer " + jwtService.generateToken(user.getUsername(),
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private User saveUser(String username, List<String> teamIds) {
        return userRepository.save(User.builder()
                .username(username).email(username + "@test.com")
                .firstName("Test").lastName(username)
                .password("n/a").loginOption(LoginOption.NATIVE)
                .teamIds(teamIds).isInternal(true)
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0)
                .build());
    }

    private Assessment saveAssessment(String name, List<String> assessorIds) {
        return assessmentRepository.save(Assessment.builder()
                .name(name).applicationId("app-1").organizationId("org-1")
                .assessmentTypeId("type-1").status("IN_PROGRESS")
                .assessorIds(assessorIds)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private PeerReview saveReview(String assessmentId, String submittedByUserId) {
        return peerReviewRepository.save(PeerReview.builder()
                .assessmentId(assessmentId)
                .submittedByUserId(submittedByUserId)
                .status(PeerReviewStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void setAllowSelfPeerReview(boolean allow) {
        AssessmentWorkflowConfig config = configRepository.findById("singleton")
                .orElseGet(() -> AssessmentWorkflowConfig.builder().id("singleton").build());
        config.setAllowSelfPeerReview(allow);
        configRepository.save(config);
    }
}
