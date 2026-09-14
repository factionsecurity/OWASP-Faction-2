package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.AssessmentType;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.SubOrganization;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.SubOrganizationRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.StorageService;
import com.jayway.jsonpath.JsonPath;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An external user's access is the union of their organization and sub-organization memberships:
 * an organization grants everything in it, a sub-organization grants only the applications
 * attributed to it. Checked end to end through the application, assessment and organization lists
 * and the single-application fetch, for both the {@code :org} and the {@code :owned} role shapes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgMembershipScopeTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private SubOrganizationRepository subOrganizationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @MockBean private StorageService storageService;

    private static final List<String> ORG_PERMS = List.of(
            Permission.APPLICATIONS_READ_ORG.getPermission(),
            Permission.ORGANIZATIONS_READ_ORG.getPermission(),
            Permission.ASSESSMENTS_READ_ORG.getPermission(),
            Permission.VULNERABILITIES_READ_ORG.getPermission());
    private static final List<String> OWNED_PERMS = List.of(
            Permission.APPLICATIONS_READ_OWNED.getPermission(),
            Permission.ORGANIZATIONS_READ_OWNED.getPermission(),
            Permission.ASSESSMENTS_READ_OWNED.getPermission(),
            Permission.VULNERABILITIES_READ_OWNED.getPermission());

    private Organization orgA, orgB, orgC;
    private SubOrganization bEmea;
    private Application aApp, bEmeaApp, bOtherApp, cApp;
    private String typeId;

    @BeforeEach
    void setUp() {
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        subOrganizationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        assessmentTypeRepository.deleteAll();

        orgA = organizationRepository.save(Organization.builder().name("A").build());
        orgB = organizationRepository.save(Organization.builder().name("B").build());
        orgC = organizationRepository.save(Organization.builder().name("C").build());
        bEmea = subOrganizationRepository.save(SubOrganization.builder()
                .organizationId(orgB.getId()).name("EMEA").createdAt(LocalDateTime.now()).build());
        aApp = app("A app", orgA.getId(), null);
        bEmeaApp = app("B EMEA app", orgB.getId(), bEmea.getId());
        bOtherApp = app("B other app", orgB.getId(), null);
        cApp = app("C app", orgC.getId(), null);
        typeId = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Pentest").createdAt(LocalDateTime.now()).build()).getId();
        for (Application a : List.of(aApp, bEmeaApp, bOtherApp, cApp)) assessment(a);
    }

    private Application app(String name, String orgId, String subOrgId) {
        return applicationRepository.save(Application.builder()
                .name(name).organizationId(orgId).subOrganizationId(subOrgId)
                .createdAt(LocalDateTime.now()).build());
    }

    private void assessment(Application a) {
        assessmentRepository.save(Assessment.builder()
                .name(a.getName() + " test").applicationId(a.getId()).organizationId(a.getOrganizationId())
                .assessmentTypeId(typeId).status("Testing")
                .fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now()).build());
    }

    private String token(List<String> orgIds, List<String> subOrgIds, List<String> perms) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
                .username("u-" + suffix).email(suffix + "@client.com").password("x")
                .loginOption(LoginOption.NATIVE).isInternal(false)
                .organizationIds(new ArrayList<>(orgIds))
                .subOrganizationIds(new ArrayList<>(subOrgIds))
                .createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        return jwtService.generateToken(u.getUsername(), perms.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private List<String> names(String path, String token) throws Exception {
        String json = mockMvc.perform(get(path).param("size", "50").param("showCompleted", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data[*].name");
    }

    private List<String> visibleAppNames(String token) throws Exception { return names("/api/v1/applications", token); }
    private List<String> visibleAssessmentNames(String token) throws Exception { return names("/api/v1/assessments", token); }
    private List<String> visibleOrgNames(String token) throws Exception { return names("/api/v1/organizations", token); }

    @Test
    void memberOfTwoOrganizations_seesBoth_ownedScope() throws Exception {
        String t = token(List.of(orgA.getId(), orgC.getId()), List.of(), OWNED_PERMS);
        assertThat(visibleAppNames(t)).containsExactlyInAnyOrder("A app", "C app");
        assertThat(visibleAssessmentNames(t)).containsExactlyInAnyOrder("A app test", "C app test");
        assertThat(visibleOrgNames(t)).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    void memberOfTwoOrganizations_seesBoth_orgScope() throws Exception {
        String t = token(List.of(orgA.getId(), orgC.getId()), List.of(), ORG_PERMS);
        assertThat(visibleAppNames(t)).containsExactlyInAnyOrder("A app", "C app");
        assertThat(visibleAssessmentNames(t)).containsExactlyInAnyOrder("A app test", "C app test");
        assertThat(visibleOrgNames(t)).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    void subOrganizationMember_seesOnlyThatSubOrgsApplications() throws Exception {
        for (List<String> perms : List.of(OWNED_PERMS, ORG_PERMS)) {
            String t = token(List.of(), List.of(bEmea.getId()), perms);
            assertThat(visibleAppNames(t)).containsExactly("B EMEA app");
            assertThat(visibleAssessmentNames(t)).containsExactly("B EMEA app test");
            mockMvc.perform(get("/api/v1/applications/" + bOtherApp.getId()).header("Authorization", "Bearer " + t))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/applications/" + bEmeaApp.getId()).header("Authorization", "Bearer " + t))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void organizationPlusForeignSubOrganization_unions() throws Exception {
        for (List<String> perms : List.of(OWNED_PERMS, ORG_PERMS)) {
            String t = token(List.of(orgA.getId()), List.of(bEmea.getId()), perms);
            assertThat(visibleAppNames(t)).containsExactlyInAnyOrder("A app", "B EMEA app");
            assertThat(visibleAssessmentNames(t)).containsExactlyInAnyOrder("A app test", "B EMEA app test");
        }
    }

    @Test
    void subOrganizationMember_seesParentOrganizationRecord_butNotItsOtherApps() throws Exception {
        String t = token(List.of(), List.of(bEmea.getId()), OWNED_PERMS);
        assertThat(visibleOrgNames(t)).containsExactly("B");
        assertThat(visibleAppNames(t)).doesNotContain("B other app");
    }

    @Test
    void noMembership_seesNothing() throws Exception {
        for (List<String> perms : List.of(OWNED_PERMS, ORG_PERMS)) {
            String t = token(List.of(), List.of(), perms);
            assertThat(visibleAppNames(t)).isEmpty();
            assertThat(visibleAssessmentNames(t)).isEmpty();
            assertThat(visibleOrgNames(t)).isEmpty();
        }
    }
}
