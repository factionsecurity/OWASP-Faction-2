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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One customer must never reach another customer's engagement.
 *
 * <p>Every request here is made by a real external account belonging to organization A, against
 * ids belonging to organization B. Each one is a route to somebody else's data that
 * {@code @RequiresPermission} lets through, because the permission is held — it is the row-level
 * check that has to stop it.
 *
 * <p>The signature that produced all of these is worth remembering: a controller handler that
 * takes a caller-supplied id and no {@code Authentication} parameter cannot be doing a scope
 * check, whatever its annotations say.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossTenantIsolationTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SubOrganizationRepository subOrganizationRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private AssessmentChecklistRepository checklistRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    @MockBean private StorageService storageService;

    private static final List<String> APP_OWNER_PERMS = List.of(
            Permission.APPLICATIONS_READ_OWNED.getPermission(),
            Permission.ORGANIZATIONS_READ_OWNED.getPermission(),
            Permission.ASSESSMENTS_READ_OWNED.getPermission(),
            Permission.VULNERABILITIES_READ_OWNED.getPermission(),
            Permission.VULNERABILITIES_COMMENT_OWNED.getPermission(),
            Permission.VULNERABILITIES_RETEST_OWNED.getPermission(),
            Permission.REPORTING_DOWNLOAD_OWNED.getPermission());

    /** Their organization. */
    private Organization orgA;
    /** Somebody else's, which this caller has no relationship to at all. */
    private Organization orgB;
    private Assessment theirAssessment;
    private Vulnerability theirVulnerability;
    private String attackerToken;

    @BeforeEach
    void setUp() {
        stageCompletionRepository.deleteAll();
        checklistRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        subOrganizationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        orgA = organizationRepository.save(Organization.builder().name("Acme").description("ours").build());
        orgB = organizationRepository.save(Organization.builder().name("Globex").description("theirs").build());

        Application theirApp = applicationRepository.save(Application.builder()
                .name("Globex Payments").description("d").organizationId(orgB.getId())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        theirAssessment = assessmentRepository.save(Assessment.builder()
                .name("Globex Q1").applicationId(theirApp.getId()).organizationId(orgB.getId())
                .assessmentTypeId("t").status("COMPLETED")
                .attachments(List.of(AssessmentFile.builder()
                        .id("their-file").fileName("scope.pdf").storageKey("k/their-file")
                        .contentType("application/pdf").build()))
                .createdAt(LocalDateTime.now()).build());
        theirVulnerability = vulnerabilityRepository.save(Vulnerability.builder()
                .name("Globex SQLi").severity(VulnerabilitySeverity.CRITICAL)
                .assessmentId(theirAssessment.getId()).order(0).status("Open")
                .openedAt(LocalDateTime.now())
                .exceptionFiles(List.of(AssessmentFile.builder()
                        .id("their-exception").fileName("risk-acceptance.pdf")
                        .storageKey("k/their-exception").contentType("application/pdf").build()))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        checklistRepository.save(AssessmentChecklist.builder()
                .assessmentId(theirAssessment.getId()).templateName("Globex methodology")
                .responses(new java.util.ArrayList<>())
                .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now()).build());
        subOrganizationRepository.save(SubOrganization.builder()
                .organizationId(orgB.getId()).name("Globex Trading Desk")
                .createdAt(LocalDateTime.now()).build());

        Role role = roleRepository.save(Role.builder()
                .name("App Owner").permissions(APP_OWNER_PERMS).externalRole(true).build());
        User attacker = userRepository.save(User.builder()
                .username("acme-owner").email("owner@acme.com")
                .password("n/a").loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(false).organizationId(orgA.getId())
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        attackerToken = jwtService.generateToken(attacker.getUsername(),
                APP_OWNER_PERMS.stream().map(SimpleGrantedAuthority::new).toList());

        when(storageService.openStream(anyString())).thenReturn(StoredObjects.of("secret-bytes"));
    }

    private void mustBeRefused(String url, Object... vars) throws Exception {
        mockMvc.perform(get(url, vars).header("Authorization", "Bearer " + attackerToken))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status < 400) {
                        throw new AssertionError("CROSS-TENANT LEAK: " + url
                                + " returned " + status + " to another customer's user. Body: "
                                + result.getResponse().getContentAsString());
                    }
                });
    }

    @Test
    void theirChecklistsAreNotReadable() throws Exception {
        // The tester's methodology, per-question results and free-text notes.
        mustBeRefused("/api/v1/assessments/{id}/checklists", theirAssessment.getId());
    }

    @Test
    void theirSubOrganizationsAreNotReadable() throws Exception {
        // Internal division names and an estate-size count — an org chart.
        mustBeRefused("/api/v1/organizations/{id}/sub-organizations", orgB.getId());
    }

    @Test
    void theirExceptionEvidenceIsNotDownloadable() throws Exception {
        mustBeRefused("/api/v1/assessments/{aid}/vulnerabilities/{vid}/exception-files/{fid}/content",
                theirAssessment.getId(), theirVulnerability.getId(), "their-exception");
    }

    @Test
    void theirAssessmentAttachmentsAreNotDownloadable() throws Exception {
        mustBeRefused("/api/v1/assessments/{aid}/files/{fid}/content",
                theirAssessment.getId(), "their-file");
    }

    @Test
    void theirRemediationTimelineIsNotReadable() throws Exception {
        mustBeRefused("/api/v1/assessments/{aid}/vulnerabilities/{vid}/stage-completions",
                theirAssessment.getId(), theirVulnerability.getId());
    }

    @Test
    void theirFindingsAreNotReadable() throws Exception {
        // The control: this one is already scoped, so it proves the fixture really is foreign.
        mustBeRefused("/api/v1/assessments/{aid}/vulnerabilities/{vid}",
                theirAssessment.getId(), theirVulnerability.getId());
    }
}
