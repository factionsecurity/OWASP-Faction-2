package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.util.StoredObjects;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssignedUser;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.faction.clientportal.service.AccessScopeService;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.StorageService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end scope tests for the owned-access model. The owner's home
 * organization (orgA) grants full access to everything in it; application
 * assignments RESTRICT the user to just those applications.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppOwnerScopeTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private RetestRepository retestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private AccessScopeService accessScopeService;

    @MockBean private StorageService storageService;

    private static final List<String> APP_OWNER_PERMS = List.of(
            Permission.APPLICATIONS_READ_OWNED.getPermission(),
            Permission.APPLICATIONS_CREATE_OWNED.getPermission(),
            Permission.ORGANIZATIONS_READ_OWNED.getPermission(),
            Permission.ASSESSMENTS_READ_OWNED.getPermission(),
            Permission.VULNERABILITIES_READ_OWNED.getPermission(),
            Permission.VULNERABILITIES_COMMENT_OWNED.getPermission(),
            Permission.VULNERABILITIES_RETEST_OWNED.getPermission(),
            Permission.REPORTING_DOWNLOAD_OWNED.getPermission(),
            Permission.SURVEYS_COMPLETE.getPermission());

    private Organization orgA;
    private Organization orgB;
    private Application app1;   // orgA — direct assignment target
    private Application app2;   // orgA — covered only by org-level assignment
    private Application app3;   // orgB — never accessible to the owner
    private Assessment assessment1;
    private Assessment assessment3;
    private Vulnerability vuln1;
    private Vulnerability vuln3;
    private User owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        orgA = organizationRepository.save(Organization.builder().name("Org A").description("a").build());
        orgB = organizationRepository.save(Organization.builder().name("Org B").description("b").build());

        app1 = saveApp("App One", orgA.getId());
        app2 = saveApp("App Two", orgA.getId());
        app3 = saveApp("App Three", orgB.getId());

        assessment1 = saveAssessment("Assessment One", app1.getId(), orgA.getId());
        assessment3 = saveAssessment("Assessment Three", app3.getId(), orgB.getId());

        vuln1 = saveVuln("XSS", assessment1.getId());
        vuln3 = saveVuln("SQLi", assessment3.getId());

        Role role = roleRepository.save(Role.builder()
                .name("App Owner").permissions(APP_OWNER_PERMS).externalRole(true).build());
        owner = userRepository.save(User.builder()
                .username("app-owner").email("owner@client.com")
                .password("n/a").loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(false).organizationId(orgA.getId())
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0)
                .build());
        ownerToken = jwtService.generateToken(owner.getUsername(),
                APP_OWNER_PERMS.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private void assignToApp(Application app, String accessLevel) {
        app.setAssignedUsers(List.of(AssignedUser.builder()
                .userId(owner.getId()).displayName("Owner").email(owner.getEmail())
                .accessLevel(accessLevel).build()));
        applicationRepository.save(app);
    }

    // ── Visibility ──────────────────────────────────────────────────────────

    @Test
    void appLevelAssignee_seesOnlyAssignedApplication() throws Exception {
        assignToApp(app1, "READ");

        mockMvc.perform(get("/api/v1/applications").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("App One"));

        mockMvc.perform(get("/api/v1/applications/" + app2.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void orgLevelUser_homeOrgGrantsAllApplicationsInIt() throws Exception {
        // no application assignments — home organization (orgA) is the grant

        mockMvc.perform(get("/api/v1/applications").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/applications/" + app3.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void orgVisibility_followsAccessMode() throws Exception {
        // org-level: sees the home organization
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // app-level restriction removes org pages
        assignToApp(app1, "READ");
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownedApplicationIds_orgLevelByDefault_restrictedByAssignments() {
        // org-level (no assignments): whole home org
        assertThat(accessScopeService.ownedApplicationIds(owner.getId()))
                .containsExactlyInAnyOrder(app1.getId(), app2.getId());

        // an assignment restricts to exactly the assigned apps — even outside the home org
        assignToApp(app3, "READ");
        assertThat(accessScopeService.ownedApplicationIds(owner.getId()))
                .containsExactlyInAnyOrder(app3.getId());
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    @Test
    void readAssignee_cannotUpdateApplication_writeAssigneeCan() throws Exception {
        assignToApp(app1, "READ");
        String body = "{\"name\":\"App One Renamed\",\"description\":\"upd\"}";

        mockMvc.perform(put("/api/v1/applications/" + app1.getId()).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        assignToApp(app1, "WRITE");
        mockMvc.perform(put("/api/v1/applications/" + app1.getId()).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void orgLevelUser_hasFullEditAccessInHomeOrg() throws Exception {
        // no assignments — home org grants edit on its applications
        String body = "{\"name\":\"App Two Renamed\",\"description\":\"upd\"}";

        mockMvc.perform(put("/api/v1/applications/" + app2.getId()).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // ── Vulnerabilities ─────────────────────────────────────────────────────

    @Test
    void vulnerabilityReads_scopedToOwnedApplications() throws Exception {
        assignToApp(app1, "READ");

        mockMvc.perform(get("/api/v1/assessments/" + assessment1.getId() + "/vulnerabilities")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/assessments/" + assessment3.getId() + "/vulnerabilities")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void vulnerabilityComments_scopedToOwnedApplications() throws Exception {
        assignToApp(app1, "READ");
        String body = "{\"content\":\"Please prioritize this\"}";

        mockMvc.perform(post("/api/v1/assessments/" + assessment1.getId()
                        + "/vulnerabilities/" + vuln1.getId() + "/comments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/assessments/" + assessment3.getId()
                        + "/vulnerabilities/" + vuln3.getId() + "/comments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ── Retests (schedule for remediation) ──────────────────────────────────

    @Test
    void retestScheduling_scopedToOwnedApplications() throws Exception {
        assignToApp(app1, "READ");
        // App owners request a retest — no dates, no assessors — exactly as the vulnerability
        // drawer does; staff schedule it later. (Scheduling now requires an assessor and dates,
        // which an app owner can't supply.) What's under test here is the owned/not-owned scope.
        String body = "{\"vulnerabilityId\":\"%s\"}";

        mockMvc.perform(post("/api/v1/assessments/" + assessment1.getId() + "/retests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(vuln1.getId())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/assessments/" + assessment3.getId() + "/retests")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(vuln3.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void retestList_filteredToOwnedApplications() throws Exception {
        assignToApp(app1, "READ");
        // one retest in scope, one out of scope (created directly)
        saveRetest(assessment1.getId(), app1.getId(), vuln1.getId());
        saveRetest(assessment3.getId(), app3.getId(), vuln3.getId());

        mockMvc.perform(get("/api/v1/retests").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessmentId").value(assessment1.getId()));
    }

    @Test
    void retestCancellation_isAllowedOnOwnedApplications() throws Exception {
        // Whoever may ask for a retest may call it off — and cancelling marks it CANCELLED
        // rather than removing it, so the finding keeps the record of what happened.
        assignToApp(app1, "READ");
        String retestId = saveRetest(assessment1.getId(), app1.getId(), vuln1.getId());

        mockMvc.perform(delete("/api/v1/retests/" + retestId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertThat(retestRepository.findById(retestId).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void retestCancellation_scopedToOwnedApplications() throws Exception {
        // The external retest permission carries no scope of its own, so without the service's
        // check this would cancel another organization's retest by id.
        assignToApp(app1, "READ");
        String otherRetestId = saveRetest(assessment3.getId(), app3.getId(), vuln3.getId());

        mockMvc.perform(delete("/api/v1/retests/" + otherRetestId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        assertThat(retestRepository.findById(otherRetestId).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
    }

    // ── Surveys ─────────────────────────────────────────────────────────────

    @Test
    void surveyAccess_scopedToOwnedApplications() throws Exception {
        assignToApp(app1, "READ");

        mockMvc.perform(get("/api/v1/assessments/" + assessment1.getId() + "/surveys")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/assessments/" + assessment3.getId() + "/surveys")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    // ── Report download ─────────────────────────────────────────────────────

    @Test
    void reportDownload_scopedToOwnedApplications() throws Exception {
        assignToApp(app1, "READ");
        assessment1.setGeneratedReportFileId("file-1");
        assessmentRepository.save(assessment1);
        assessment3.setGeneratedReportFileId("file-3");
        assessmentRepository.save(assessment3);
        when(storageService.openStream(anyString())).thenReturn(StoredObjects.of("report-bytes"));

        mockMvc.perform(get("/api/v1/reports/" + assessment1.getId() + "/documents/DOCX/content")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reports/" + assessment3.getId() + "/documents/DOCX/content")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private Application saveApp(String name, String orgId) {
        return applicationRepository.save(Application.builder()
                .name(name).description("test app").organizationId(orgId)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    private Assessment saveAssessment(String name, String appId, String orgId) {
        return assessmentRepository.save(Assessment.builder()
                .name(name).applicationId(appId).organizationId(orgId)
                .assessmentTypeId("type-1").status("IN_PROGRESS")
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Vulnerability saveVuln(String name, String assessmentId) {
        return vulnerabilityRepository.save(Vulnerability.builder()
                .name(name).severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessmentId).order(0)
                .openedAt(LocalDateTime.now()).status("Open")
                .createdBy("system").lastUpdatedBy("system")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    private String saveRetest(String assessmentId, String appId, String vulnId) {
        return retestRepository.save(com.faction.clientportal.model.Retest.builder()
                .assessmentId(assessmentId).applicationId(appId).vulnerabilityId(vulnId)
                .scheduledStartDate(LocalDateTime.now().plusDays(1))
                .scheduledEndDate(LocalDateTime.now().plusDays(2))
                .status("SCHEDULED").createdBy("system").lastUpdatedBy("system")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build()).getId();
    }
}
