package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateAssessmentRequest;
import com.faction.clientportal.dto.CreateNotebookNodeRequest;
import com.faction.clientportal.dto.MoveNotebookNodeRequest;
import com.faction.clientportal.dto.UpdateNotebookNodeRequest;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotebookControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotebookNodeRepository notebookNodeRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private AssessmentTypeRepository assessmentTypeRepository;

    @Autowired
    private ReportTemplateRepository reportTemplateRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Role superAdminRole;
    private User testUser;
    private String jwtToken;
    private Organization testOrganization;
    private Application testApplication;
    private AssessmentType testAssessmentType;
    private ReportTemplate testTemplate;

    @BeforeEach
    void setUp() {
        notebookNodeRepository.deleteAll();
        assessmentRepository.deleteAll();
        reportTemplateRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // Create SuperAdmin role
        superAdminRole = Role.builder()
                .name("SuperAdmin")
                .description("Super Administrator with full access")
                .permissions(List.of("super_admin"))
                .build();
        superAdminRole = roleRepository.save(superAdminRole);

        // Create test user
        testUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .password(passwordEncoder.encode("password"))
                .firstName("Test")
                .lastName("User")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(superAdminRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        testUser = userRepository.save(testUser);

        // Generate JWT token
        jwtToken = jwtService.generateToken(
                testUser.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin"))
        );

        // Create test organization
        testOrganization = Organization.builder()
                .name("Test Organization")
                .description("Test Description")
                .build();
        testOrganization = organizationRepository.save(testOrganization);

        // Create test application
        testApplication = Application.builder()
                .name("Test Application")
                .description("Test App Description")
                .organizationId(testOrganization.getId())
                .createdAt(LocalDateTime.now())
                .build();
        testApplication = applicationRepository.save(testApplication);

        // Create test assessment type
        testAssessmentType = AssessmentType.builder()
                .name("Penetration Test")
                .description("Security assessment")
                .createdAt(LocalDateTime.now())
                .build();
        testAssessmentType = assessmentTypeRepository.save(testAssessmentType);

        // Create test report template
        testTemplate = ReportTemplate.builder()
                .name("Test Template")
                .description("Test template description")
                .assessmentTypeId(testAssessmentType.getId())
                .version(1)
                .active(true)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
        testTemplate = reportTemplateRepository.save(testTemplate);
    }

    // -------------------------------------------------------------------------
    // 1. Empty tree
    // -------------------------------------------------------------------------

    @Test
    void testGetTree_EmptyForNewApplication() throws Exception {
        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // -------------------------------------------------------------------------
    // 2. Create root node
    // -------------------------------------------------------------------------

    @Test
    void testCreateNode_Success() throws Exception {
        CreateNotebookNodeRequest request = new CreateNotebookNodeRequest();
        request.setTitle("My First Note");
        request.setContent("<p>Hello world</p>");

        mockMvc.perform(post("/api/v1/applications/" + testApplication.getId() + "/notebook/nodes")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("My First Note"))
                .andExpect(jsonPath("$.data.applicationId").value(testApplication.getId()))
                .andExpect(jsonPath("$.data.depth").value(0))
                .andExpect(jsonPath("$.data.createdById").value(testUser.getUsername()))
                .andExpect(jsonPath("$.data.createdByName").value("Test User"));
    }

    // -------------------------------------------------------------------------
    // 3. Max depth enforcement
    // -------------------------------------------------------------------------

    @Test
    void testCreateNode_MaxDepthEnforced() throws Exception {
        // Create a chain of 6 nodes (depth 0..5) — that is MAX_DEPTH
        String parentId = null;
        for (int i = 0; i <= 5; i++) {
            parentId = createNodeViaApi(testApplication.getId(), "Level " + i, parentId);
        }

        // Attempting to create a child at depth 6 (parent depth=5) should fail
        CreateNotebookNodeRequest tooDeep = new CreateNotebookNodeRequest();
        tooDeep.setTitle("Too Deep");
        tooDeep.setParentId(parentId);

        mockMvc.perform(post("/api/v1/applications/" + testApplication.getId() + "/notebook/nodes")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooDeep)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 4. Create child node
    // -------------------------------------------------------------------------

    @Test
    void testCreateChildNode_Success() throws Exception {
        String parentId = createNodeViaApi(testApplication.getId(), "Parent Node", null);

        CreateNotebookNodeRequest childRequest = new CreateNotebookNodeRequest();
        childRequest.setTitle("Child Node");
        childRequest.setContent("<p>child content</p>");
        childRequest.setParentId(parentId);

        mockMvc.perform(post("/api/v1/applications/" + testApplication.getId() + "/notebook/nodes")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(childRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentId").value(parentId))
                .andExpect(jsonPath("$.data.depth").value(1));
    }

    // -------------------------------------------------------------------------
    // 5. Update node
    // -------------------------------------------------------------------------

    @Test
    void testUpdateNode_Success() throws Exception {
        String nodeId = createNodeViaApi(testApplication.getId(), "Original Title", null);

        UpdateNotebookNodeRequest update = new UpdateNotebookNodeRequest();
        update.setTitle("Updated Title");
        update.setContent("<p>Updated content</p>");

        mockMvc.perform(put("/api/v1/notebook/nodes/" + nodeId)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Updated Title"))
                .andExpect(jsonPath("$.data.modifiedBy", hasSize(1)))
                .andExpect(jsonPath("$.data.modifiedBy[0].userId").value(testUser.getUsername()));
    }

    // -------------------------------------------------------------------------
    // 6. Delete node (soft delete)
    // -------------------------------------------------------------------------

    @Test
    void testDeleteNode_Success() throws Exception {
        String nodeId = createNodeViaApi(testApplication.getId(), "Node to Delete", null);

        mockMvc.perform(delete("/api/v1/notebook/nodes/" + nodeId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Node should no longer appear in the tree
        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // -------------------------------------------------------------------------
    // 7. Delete cascades children
    // -------------------------------------------------------------------------

    @Test
    void testDeleteNode_CascadesChildren() throws Exception {
        String parentId = createNodeViaApi(testApplication.getId(), "Parent", null);
        String childId = createNodeViaApi(testApplication.getId(), "Child", parentId);
        createNodeViaApi(testApplication.getId(), "Grandchild", childId);

        // Delete parent
        mockMvc.perform(delete("/api/v1/notebook/nodes/" + parentId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        // All three nodes should be soft-deleted
        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // -------------------------------------------------------------------------
    // 8. Move node
    // -------------------------------------------------------------------------

    @Test
    void testMoveNode_Success() throws Exception {
        String nodeA = createNodeViaApi(testApplication.getId(), "Node A", null);
        String nodeB = createNodeViaApi(testApplication.getId(), "Node B", null);

        // Move nodeB under nodeA
        MoveNotebookNodeRequest moveRequest = new MoveNotebookNodeRequest();
        moveRequest.setNewParentId(nodeA);
        moveRequest.setNewOrderIndex(0);

        mockMvc.perform(put("/api/v1/notebook/nodes/" + nodeB + "/move")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(moveRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentId").value(nodeA))
                .andExpect(jsonPath("$.data.depth").value(1));
    }

    // -------------------------------------------------------------------------
    // 9. Search by text
    // -------------------------------------------------------------------------

    @Test
    void testSearchNodes_ByText() throws Exception {
        // Create a node with distinctive content
        NotebookNode node = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .title("XSS Testing Notes")
                .content("<p>xss payload</p>")
                .contentText("xss payload")
                .orderIndex(0)
                .depth(0)
                .attachments(new ArrayList<>())
                .modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(testUser.getId())
                .createdByName("Test User")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        notebookNodeRepository.save(node);

        // Create another node that should NOT match
        NotebookNode other = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .title("Unrelated Note")
                .content("<p>something else</p>")
                .contentText("something else")
                .orderIndex(1)
                .depth(0)
                .attachments(new ArrayList<>())
                .modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(testUser.getId())
                .createdByName("Test User")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        notebookNodeRepository.save(other);

        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook/search")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("q", "xss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].node.title").value("XSS Testing Notes"));
    }

    // -------------------------------------------------------------------------
    // 10. Search by createdById
    // -------------------------------------------------------------------------

    @Test
    void testSearchNodes_ByCreatedBy() throws Exception {
        // Create a second user
        User otherUser = User.builder()
                .username("otheruser")
                .email("other@example.com")
                .password(passwordEncoder.encode("password"))
                .firstName("Other")
                .lastName("Person")
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        otherUser = userRepository.save(otherUser);

        NotebookNode nodeA = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .title("Node by testUser")
                .content("").contentText("")
                .orderIndex(0).depth(0)
                .attachments(new ArrayList<>()).modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(testUser.getId())
                .createdByName("Test User")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        notebookNodeRepository.save(nodeA);

        NotebookNode nodeB = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .title("Node by otherUser")
                .content("").contentText("")
                .orderIndex(1).depth(0)
                .attachments(new ArrayList<>()).modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(otherUser.getId())
                .createdByName("Other Person")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        notebookNodeRepository.save(nodeB);

        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook/search")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("createdById", otherUser.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].node.title").value("Node by otherUser"));
    }

    // -------------------------------------------------------------------------
    // 11. Assessment roots without content are excluded from tree
    // -------------------------------------------------------------------------

    @Test
    void testGetTree_OnlyShowsPreviousRootsWithContent() throws Exception {
        // An assessment-owned root with NO children should be excluded
        NotebookNode emptyRoot = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .assessmentId("some-assessment-id")
                .title("Empty Assessment Root")
                .content("").contentText("")
                .orderIndex(0).depth(0)
                .attachments(new ArrayList<>()).modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(testUser.getId())
                .createdByName("Test User")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        emptyRoot = notebookNodeRepository.save(emptyRoot);

        // An assessment-owned root WITH a child should appear
        NotebookNode rootWithChild = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .assessmentId("another-assessment-id")
                .title("Assessment Root With Notes")
                .content("").contentText("")
                .orderIndex(1).depth(0)
                .attachments(new ArrayList<>()).modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(testUser.getId())
                .createdByName("Test User")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        rootWithChild = notebookNodeRepository.save(rootWithChild);

        // Add a child to the second root
        NotebookNode child = NotebookNode.builder()
                .applicationId(testApplication.getId())
                .parentId(rootWithChild.getId())
                .title("A child note")
                .content("<p>notes here</p>").contentText("notes here")
                .orderIndex(0).depth(1)
                .attachments(new ArrayList<>()).modifiedBy(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .createdById(testUser.getId())
                .createdByName("Test User")
                .lastModifiedAt(LocalDateTime.now())
                .build();
        notebookNodeRepository.save(child);

        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("Assessment Root With Notes"));
    }

    // -------------------------------------------------------------------------
    // 12. Unauthorized access
    // -------------------------------------------------------------------------

    @Test
    void testUnauthorizedAccess() throws Exception {
        // Generate a token with no assessment permissions
        User limitedUser = User.builder()
                .username("limiteduser")
                .email("limited@example.com")
                .password(passwordEncoder.encode("password"))
                .firstName("Limited")
                .lastName("User")
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .build();
        limitedUser = userRepository.save(limitedUser);

        String limitedToken = jwtService.generateToken(
                limitedUser.getUsername(),
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all"))
        );

        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook")
                        .header("Authorization", "Bearer " + limitedToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // 13. Assessment creation auto-creates root notebook node
    // -------------------------------------------------------------------------

    @Test
    void testCreateAssessment_AutoCreatesRootNotebookNode() throws Exception {
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("Auto Notebook Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .assessorIds(List.of(testUser.getId()))
                .startDate(LocalDateTime.of(2025, 6, 15, 0, 0))
                .build();

        mockMvc.perform(post("/api/v1/assessments")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // The notebook tree for this application should now have exactly one root node
        mockMvc.perform(get("/api/v1/applications/" + testApplication.getId() + "/notebook")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title", containsString("Auto Notebook Assessment")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Create a notebook node via the API and return its id.
     */
    private String createNodeViaApi(String appId, String title, String parentId) throws Exception {
        CreateNotebookNodeRequest request = new CreateNotebookNodeRequest();
        request.setTitle(title);
        request.setContent("");
        request.setParentId(parentId);

        String response = mockMvc.perform(post("/api/v1/applications/" + appId + "/notebook/nodes")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("data").get("id").asText();
    }
}
