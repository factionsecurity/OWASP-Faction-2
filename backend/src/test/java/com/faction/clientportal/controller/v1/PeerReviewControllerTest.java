package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate coverage for {@link PeerReviewController}: which permissions open which endpoints. The
 * gates accept both the ":all" and the scope-narrowed variants; PeerReviewService then enforces
 * the scope (team membership, assessor-only submit, self-review) — see PeerReviewTeamScopeTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PeerReviewControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    private String tokenWith(String... authorities) {
        return "Bearer " + jwtService.generateToken("pr-user",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void queue_withPeerreviewReadAll_isAllowed() throws Exception {
        // The gate accepts only the ":all" scope (":team" is not enforced). read:all is a read
        // permission, so a read-only super-admin key carries it via the read-universe expansion.
        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", tokenWith("peerreview:read:all")))
                .andExpect(status().isOk());
    }

    @Test
    void queue_withPeerreviewReadTeam_isAllowedAndScoped() throws Exception {
        // peerreview:read:team is now accepted: PeerReviewService filters the queue to reviews whose
        // assessment shares a team with the caller. This principal has no user record and therefore
        // no teams, so the queue comes back empty rather than 403 (see PeerReviewTeamScopeTest for
        // the filtering itself).
        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", tokenWith("peerreview:read:team")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void queue_withoutPeerreviewPermission_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/peer-reviews/queue")
                        .header("Authorization", tokenWith("assessments:read:team")))
                .andExpect(status().isForbidden());
    }

    // create vs edit must be distinct permissions, not synonyms.

    @Test
    void submit_requiresCreate_notEdit() throws Exception {
        // Submitting an assessment for review is a CREATE; peerreview:edit:all must not authorize it.
        mockMvc.perform(post("/api/v1/assessments/nonexistent/peer-reviews/submit")
                        .header("Authorization", tokenWith("peerreview:edit:all")))
                .andExpect(status().isForbidden());
        // peerreview:create:all authorizes it (then 404 — the assessment doesn't exist).
        mockMvc.perform(post("/api/v1/assessments/nonexistent/peer-reviews/submit")
                        .header("Authorization", tokenWith("peerreview:create:all")))
                .andExpect(status().isNotFound());
    }

    @Test
    void editEndpoint_requiresEdit_notCreate() throws Exception {
        // Working on an existing review (start) is an EDIT; peerreview:create:all must not authorize it.
        mockMvc.perform(post("/api/v1/peer-reviews/nonexistent/start")
                        .header("Authorization", tokenWith("peerreview:create:all")))
                .andExpect(status().isForbidden());
        // peerreview:edit:all authorizes it (then 404 — the review doesn't exist).
        mockMvc.perform(post("/api/v1/peer-reviews/nonexistent/start")
                        .header("Authorization", tokenWith("peerreview:edit:all")))
                .andExpect(status().isNotFound());
    }
}
