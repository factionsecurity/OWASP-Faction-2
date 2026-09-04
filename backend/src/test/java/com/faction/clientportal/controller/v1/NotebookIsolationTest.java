package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.StorageService;
import com.faction.clientportal.util.StoredObjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notebooks hold working material — credentials, tokens, exploitation steps — the things kept
 * deliberately out of the report. Every route into one is reached by an application id, a node id
 * or an attachment id, and none of the three used to be checked against who was asking.
 *
 * <p>Two callers here, because the separation model has to hold for both shapes of customer: a
 * consulting install where organization A and organization B are different client companies, and
 * an enterprise install where they are internal business units. Neither may reach the other's
 * notebook, and an internal assignment-scoped tester may not reach an application that is not
 * theirs either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotebookIsolationTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private NotebookNodeRepository notebookNodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    @MockBean private StorageService storageService;

    /** An internal tester scoped to their own assigned work — the role every install has. */
    private String testerToken;
    /** An external app owner belonging to organization A. */
    private String orgAOwnerToken;

    private String theirAppId;
    private String theirNodeId;

    @BeforeEach
    void setUp() {
        notebookNodeRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Organization orgA = organizationRepository.save(
                Organization.builder().name("Org A").description("ours").build());
        Organization orgB = organizationRepository.save(
                Organization.builder().name("Org B").description("theirs").build());

        Application theirs = applicationRepository.save(Application.builder()
                .name("Org B Payments").description("d").organizationId(orgB.getId())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        theirAppId = theirs.getId();

        // An engagement on that application, run by someone else. Without it the notebook would
        // fall into the "nothing tested here yet" allowance and the test would prove nothing.
        assessmentRepository.save(Assessment.builder()
                .name("Org B Q1").applicationId(theirAppId).organizationId(orgB.getId())
                .assessmentTypeId("t").status("IN_PROGRESS")
                .assessorIds(List.of("someone-else"))
                .createdAt(LocalDateTime.now()).build());

        theirNodeId = notebookNodeRepository.save(NotebookNode.builder()
                .applicationId(theirAppId).title("Working notes")
                .content("<p>Creds found: admin / hunter2</p>").contentText("creds")
                .orderIndex(0).depth(0)
                .attachments(List.of(NotebookAttachment.builder()
                        .id("their-attachment").fileName("dump.txt").storageKey("k/dump")
                        .contentType("text/plain").build()))
                .modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now()).lastModifiedAt(LocalDateTime.now()).build()).getId();

        testerToken = internalToken("tester",
                List.of("assessments:read:assigned", "assessments:edit:assigned"));
        orgAOwnerToken = externalToken("orgA-owner", orgA.getId(),
                List.of("applications:read:owned", "assessments:read:owned", "assessments:edit:assigned"));

        when(storageService.openStream(anyString())).thenReturn(StoredObjects.of("their-bytes"));
    }

    private void refusedForBoth(MockHttpServletRequestBuilder request, String what) throws Exception {
        for (String[] caller : new String[][] {
                {"an internal assignment-scoped tester", testerToken},
                {"an app owner from another organization", orgAOwnerToken}}) {
            mockMvc.perform(request.header("Authorization", "Bearer " + caller[1]))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status < 400) {
                            throw new AssertionError("NOTEBOOK LEAK: " + what + " was served to "
                                    + caller[0] + " (HTTP " + status + "): "
                                    + result.getResponse().getContentAsString());
                        }
                    });
        }
    }

    @Test
    void theNotebookTreeIsNotReadable() throws Exception {
        refusedForBoth(get("/api/v1/applications/{id}/notebook", theirAppId), "the notebook tree");
    }

    @Test
    void aSingleNoteIsNotReadable() throws Exception {
        refusedForBoth(get("/api/v1/notebook/nodes/{id}", theirNodeId), "a note's contents");
    }

    @Test
    void theNotebookIsNotSearchable() throws Exception {
        // Search is its own way in: it returns matching content without needing a node id.
        refusedForBoth(get("/api/v1/applications/{id}/notebook/search?q=creds", theirAppId),
                "notebook search results");
    }

    @Test
    void anAttachmentIsNotDownloadable() throws Exception {
        refusedForBoth(get("/api/v1/notebook/nodes/{n}/files/{f}/content", theirNodeId, "their-attachment"),
                "a notebook attachment");
    }

    @Test
    void aNoteCannotBeEditedOrDeleted() throws Exception {
        refusedForBoth(put("/api/v1/notebook/nodes/{id}", theirNodeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"<p>overwritten</p>\"}"), "an edit to a note");
        refusedForBoth(delete("/api/v1/notebook/nodes/{id}", theirNodeId), "deletion of a note");
    }

    @Test
    void aNoteCannotBeCreatedUnderSomeoneElsesApplication() throws Exception {
        refusedForBoth(post("/api/v1/applications/{id}/notebook/nodes", theirAppId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"planted\",\"content\":\"<p>x</p>\"}"),
                "a note planted under their application");
    }

    @Test
    void theOwningTeamCanStillUseTheirOwnNotebook() throws Exception {
        // The control. Without it, refusing everything would look like a pass.
        String adminToken = internalToken("admin", List.of("super_admin"));

        mockMvc.perform(get("/api/v1/applications/{id}/notebook", theirAppId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notebook/nodes/{id}", theirNodeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private String internalToken(String username, List<String> perms) {
        Role role = roleRepository.save(Role.builder()
                .name(username + "-role").permissions(perms).build());
        User user = userRepository.save(User.builder()
                .username(username).email(username + "@test.com").password("x")
                .loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(true).teamIds(List.of())
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        return jwtService.generateToken(username,
                perms.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private String externalToken(String username, String organizationId, List<String> perms) {
        Role role = roleRepository.save(Role.builder()
                .name(username + "-role").permissions(perms).externalRole(true).build());
        userRepository.save(User.builder()
                .username(username).email(username + "@test.com").password("x")
                .loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(false).organizationId(organizationId)
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        return jwtService.generateToken(username,
                perms.stream().map(SimpleGrantedAuthority::new).toList());
    }
}
