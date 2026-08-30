package com.faction.clientportal.service;

import com.faction.clientportal.dto.*;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.repository.AssessmentSearchCriteria;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private AssessmentTypeRepository assessmentTypeRepository;

    @Mock
    private com.faction.clientportal.repository.TeamRepository teamRepository;

    @Mock
    private ReportTemplateRepository reportTemplateRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private InlineImageService inlineImageService;

    @Mock
    private VulnerabilityRepository vulnerabilityRepository;

    @Mock
    private AssessmentWorkflowConfigService workflowConfigService;

    @Mock
    private NotebookService notebookService;

    @Mock
    private com.faction.clientportal.repository.AssessmentChecklistRepository assessmentChecklistRepository;

    @Mock
    private com.faction.clientportal.repository.ChecklistTemplateRepository checklistTemplateRepository;

    @Mock
    private ApplicationIdConfigService applicationIdConfigService;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private AccessScopeService accessScopeService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private com.faction.clientportal.service.extension.ExtensionEventService extensionEventService;

    @InjectMocks
    private AssessmentService assessmentService;

    private Assessment testAssessment;
    private Application testApplication;
    private AssessmentType testAssessmentType;
    private ReportTemplate testTemplate;
    private Organization testOrganization;
    private User testUser;

    @BeforeEach
    void setUp() {
        // These tests cover assessment behaviour, not authorization — the scope tiers have their
        // own coverage in AssessmentAccessScopeTest — so the caller always sees everything here.
        lenient().when(accessScopeService.resolveAssessmentScope(any()))
                .thenReturn(new AccessScopeService.AssessmentScope(
                        AccessScopeService.AssessmentScopeKind.UNRESTRICTED, null, null, null, null));

        // Create test organization
        testOrganization = Organization.builder()
                .id("org-1")
                .name("Test Organization")
                .description("Test Org")
                .build();

        // Create test application
        testApplication = Application.builder()
                .id("app-1")
                .name("Test Application")
                .description("Test App")
                .organizationId(testOrganization.getId())
                .stakeHolders(List.of(
                        new Stakeholder("John Doe", "john@example.com", "Product Owner"),
                        new Stakeholder("Jane Smith", "jane@example.com", "Tech Lead")
                ))
                .createdAt(LocalDateTime.now())
                .build();

        // Create test assessment type
        testAssessmentType = AssessmentType.builder()
                .id("type-1")
                .name("Penetration Test")
                .description("Security assessment")
                .createdAt(LocalDateTime.now())
                .build();

        // Create test report template
        testTemplate = ReportTemplate.builder()
                .id("template-1")
                .name("Standard Template")
                .description("Standard assessment template")
                .assessmentTypeId(testAssessmentType.getId())
                .version(1)
                .active(true)
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();

        // Create test user
        testUser = User.builder()
                .id("user-1")
                .username("testuser")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .createdAt(LocalDateTime.now())
                .build();

        // Create test assessment
        testAssessment = Assessment.builder()
                .id("assessment-1")
                .name("Test Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .reportTemplateVersion(testTemplate.getVersion())
                .templateName(testTemplate.getName())
                .status("DRAFT")
                .assessorIds(List.of(testUser.getId()))
                .engagementManagerId(testUser.getId())
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .scope("Test scope")
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .engagementUrls(new ArrayList<>())
                .stakeholders(new ArrayList<>())
                .createdBy("testuser")
                .createdAt(LocalDateTime.now())
                .build();

        // Stub workflow config service for all tests
        AssessmentWorkflowConfig defaultConfig = AssessmentWorkflowConfig.builder().id("singleton").build();
        lenient().when(workflowConfigService.getConfig()).thenReturn(defaultConfig);
        lenient().when(workflowConfigService.isCompletedStatus(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s != null && ("Completed".equals(s) || "COMPLETED".equals(s)
                    || "APPROVED".equals(s) || "ARCHIVED".equals(s));
        });
    }

    @Test
    void testCreateAssessment_Success() {
        // Given
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .assessorIds(List.of(testUser.getId()))
                .engagementManagerId(testUser.getId())
                .startDate(LocalDateTime.now())
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .scope("Assessment scope")
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(testAssessment);

        // When
        AssessmentDto result = assessmentService.createAssessment(request, "testuser");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(testAssessment.getName());
        assertThat(result.getAssessorIds()).hasSize(1);
        assertThat(result.getEngagementManagerId()).isEqualTo(testUser.getId());
        assertThat(result.getScope()).isEqualTo("Test scope");

        verify(assessmentRepository).save(any(Assessment.class));
    }

    @Test
    void testCreateAssessment_SetsTeamId() {
        // Given
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .teamId("team-1")
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Team team = Team.builder().id("team-1").name("Red Team").build();
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(team));

        // When
        AssessmentDto result = assessmentService.createAssessment(request, "testuser");

        // Then
        assertThat(result.getTeamId()).isEqualTo("team-1");
        assertThat(result.getTeamName()).isEqualTo("Red Team");
    }

    @Test
    void testCreateAssessment_AnnouncesInApplicationChat() {
        // Given
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(testAssessment);

        // When
        assessmentService.createAssessment(request, "testuser");

        // Then
        verify(applicationService).addSystemComment(
                eq(testApplication.getId()),
                contains("**Assessment scheduled**"),
                eq("testuser"));
    }

    @Test
    void testCreateAssessment_ApplicationNotFound() {
        // Given
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId("non-existent")
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById("non-existent"))
                .thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assessmentService.createAssessment(request, "testuser"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Application not found");
    }

    @Test
    void testCreateAssessment_CopiesStakeholdersFromApplication() {
        // Given
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .assessorIds(List.of(testUser.getId()))
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));

        Assessment savedAssessment = Assessment.builder()
                .id("assessment-2")
                .name(request.getName())
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .reportTemplateVersion(testTemplate.getVersion())
                .templateName(testTemplate.getName())
                .status("DRAFT")
                .assessorIds(request.getAssessorIds())
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .stakeholders(List.of(
                        new Stakeholder("John Doe", "john@example.com", "Product Owner"),
                        new Stakeholder("Jane Smith", "jane@example.com", "Tech Lead")
                ))
                .createdBy("testuser")
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(savedAssessment);

        // When
        AssessmentDto result = assessmentService.createAssessment(request, "testuser");

        // Then
        assertThat(result.getStakeholders()).hasSize(2);
        assertThat(result.getStakeholders().get(0).getName()).isEqualTo("John Doe");
        assertThat(result.getStakeholders().get(1).getName()).isEqualTo("Jane Smith");
    }

    @Test
    void testCreateAssessment_ResolvesApplicationByAppId() {
        // Given — no applicationId, but an appId matching an existing application
        testApplication.setAppId("ASMT-1");
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .appId("ASMT-1")
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findByAppId("ASMT-1"))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(testAssessment);

        // When
        AssessmentDto result = assessmentService.createAssessment(request, "testuser");

        // Then — resolved to the existing app, no new application created
        assertThat(result).isNotNull();
        verify(applicationRepository, never()).save(any(Application.class));
        ArgumentCaptor<Assessment> captor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo(testApplication.getId());
    }

    @Test
    void testCreateAssessment_AutoCreatesApplicationWhenAppIdNotFound() {
        // Given — an appId that doesn't exist, plus an explicit application name
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .appId("CUSTOM-9")
                .applicationName("Brand New App")
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findByAppId("CUSTOM-9"))
                .thenReturn(Optional.empty());
        when(applicationRepository.save(any(Application.class)))
                .thenAnswer(inv -> {
                    Application app = inv.getArgument(0);
                    app.setId("new-app-id");
                    return app;
                });
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(testAssessment);

        // When
        assessmentService.createAssessment(request, "testuser");

        // Then — application auto-created with the typed appId and given name
        ArgumentCaptor<Application> appCaptor = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(appCaptor.capture());
        assertThat(appCaptor.getValue().getAppId()).isEqualTo("CUSTOM-9");
        assertThat(appCaptor.getValue().getName()).isEqualTo("Brand New App");
        // Typed appId is used as-is — no generation
        verify(applicationIdConfigService, never()).generateNextAppId();
    }

    @Test
    void testCreateAssessment_AutoCreatesApplicationFromAssessmentName() {
        // Given — neither applicationId nor appId; assessment name doubles as app name
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("Quarterly Pentest")
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationIdConfigService.isEnabled()).thenReturn(true);
        when(applicationIdConfigService.generateNextAppId()).thenReturn("ASMT-7");
        when(applicationRepository.save(any(Application.class)))
                .thenAnswer(inv -> {
                    Application app = inv.getArgument(0);
                    app.setId("new-app-id");
                    return app;
                });
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(testAssessment);

        // When
        assessmentService.createAssessment(request, "testuser");

        // Then — application auto-created with generated appId and assessment name
        ArgumentCaptor<Application> appCaptor = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(appCaptor.capture());
        assertThat(appCaptor.getValue().getAppId()).isEqualTo("ASMT-7");
        assertThat(appCaptor.getValue().getName()).isEqualTo("Quarterly Pentest");
    }

    @Test
    void testCreateAssessment_NoAppIdGeneratedWhenConfigDisabled() {
        // Given — auto-creation path with appId generation disabled
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("Quarterly Pentest")
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationIdConfigService.isEnabled()).thenReturn(false);
        when(applicationRepository.save(any(Application.class)))
                .thenAnswer(inv -> {
                    Application app = inv.getArgument(0);
                    app.setId("new-app-id");
                    return app;
                });
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(testTemplate.getId()))
                .thenReturn(Optional.of(testTemplate));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(testAssessment);

        // When
        assessmentService.createAssessment(request, "testuser");

        // Then — application created without an appId
        ArgumentCaptor<Application> appCaptor = ArgumentCaptor.forClass(Application.class);
        verify(applicationRepository).save(appCaptor.capture());
        assertThat(appCaptor.getValue().getAppId()).isNull();
        verify(applicationIdConfigService, never()).generateNextAppId();
    }

    @Test
    void testCreateAssessment_ThrowsWhenNoApplicationAndNoName() {
        // Given — nothing to resolve or create an application from
        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(testTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        // When/Then
        assertThatThrownBy(() -> assessmentService.createAssessment(request, "testuser"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicationId or appId");
        verify(assessmentRepository, never()).save(any(Assessment.class));
    }

    @Test
    void testUpdateAssessment_ChangesApplication() {
        // Given — an assessment being moved to a different application
        Application newApplication = Application.builder()
                .id("app-2")
                .name("Other Application")
                .organizationId("org-2")
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(applicationRepository.findById("app-2"))
                .thenReturn(Optional.of(newApplication));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .applicationId("app-2")
                .build();

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then — application changed and organization follows the new app
        ArgumentCaptor<Assessment> captor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo("app-2");
        assertThat(captor.getValue().getOrganizationId()).isEqualTo("org-2");
    }

    @Test
    void testUpdateAssessment_UnknownApplicationRejected() {
        // Given
        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(applicationRepository.findById("missing-app"))
                .thenReturn(Optional.empty());

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .applicationId("missing-app")
                .build();

        // When/Then
        assertThatThrownBy(() -> assessmentService.updateAssessment(testAssessment.getId(), request, "testuser"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Application not found");
        verify(assessmentRepository, never()).save(any(Assessment.class));
    }

    @Test
    void testUpdateAssessment_ChangesTeam() {
        // Given
        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .teamId("team-2")
                .build();

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then
        ArgumentCaptor<Assessment> captor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertThat(captor.getValue().getTeamId()).isEqualTo("team-2");
    }

    @Test
    void testUpdateAssessment_ClearsTeamWhenBlank() {
        // Given — an assessment previously assigned to a team
        testAssessment.setTeamId("team-1");
        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .teamId("")
                .build();

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then — an explicit blank value unassigns the team
        ArgumentCaptor<Assessment> captor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertThat(captor.getValue().getTeamId()).isNull();
    }

    @Test
    void testGetMetrics_Success() {
        // Given — build a list of assessments with various statuses for findAll()
        List<Assessment> allAssessments = new ArrayList<>();
        for (int i = 0; i < 5; i++) allAssessments.add(Assessment.builder().id("d-" + i).status("DRAFT").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());
        for (int i = 0; i < 3; i++) allAssessments.add(Assessment.builder().id("ip-" + i).status("IN_PROGRESS").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());
        for (int i = 0; i < 2; i++) allAssessments.add(Assessment.builder().id("oh-" + i).status("ON_HOLD").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());
        allAssessments.add(Assessment.builder().id("pr-1").status("PENDING_REVIEW").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());
        for (int i = 0; i < 4; i++) allAssessments.add(Assessment.builder().id("c-" + i).status("COMPLETED").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());
        for (int i = 0; i < 2; i++) allAssessments.add(Assessment.builder().id("ap-" + i).status("APPROVED").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());
        allAssessments.add(Assessment.builder().id("ar-1").status("ARCHIVED").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build());

        when(assessmentRepository.findAll()).thenReturn(allAssessments);

        Assessment pastDueAssessment = Assessment.builder()
                .id("past-due-1")
                .name("Past Due Assessment")
                .applicationId(testApplication.getId())
                .organizationId(testOrganization.getId())
                .status("IN_PROGRESS")
                .plannedEndDate(LocalDateTime.now().minusDays(1))
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findPastDue(any(LocalDateTime.class)))
                .thenReturn(List.of(pastDueAssessment));

        // When
        AssessmentMetricsDto metrics = assessmentService.getMetrics(null);

        // Then
        assertThat(metrics.getTotalCount()).isEqualTo(18L);
        assertThat(metrics.getDraftCount()).isEqualTo(5L);
        assertThat(metrics.getInProgressCount()).isEqualTo(3L);
        assertThat(metrics.getOnHoldCount()).isEqualTo(2L);
        assertThat(metrics.getPendingReviewCount()).isEqualTo(1L);
        assertThat(metrics.getCompletedCount()).isEqualTo(4L);
        assertThat(metrics.getApprovedCount()).isEqualTo(2L);
        assertThat(metrics.getArchivedCount()).isEqualTo(1L);
        assertThat(metrics.getPastDueCount()).isEqualTo(1L);
    }

    @Test
    void testGetMetrics_WithOrganizationFilter() {
        // Given — two assessments in org-1, one in another org
        String orgId = testOrganization.getId();
        Assessment a1 = Assessment.builder().id("m1").status("DRAFT").organizationId(orgId).fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build();
        Assessment a2 = Assessment.builder().id("m2").status("DRAFT").organizationId(orgId).fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build();
        Assessment a3 = Assessment.builder().id("m3").status("IN_PROGRESS").organizationId(orgId).fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build();
        Assessment other = Assessment.builder().id("m4").status("DRAFT").organizationId("other-org").fieldDefinitions(new ArrayList<>()).fieldValues(new HashMap<>()).createdAt(LocalDateTime.now()).build();

        when(assessmentRepository.findAll()).thenReturn(List.of(a1, a2, a3, other));
        when(assessmentRepository.findPastDue(any(LocalDateTime.class))).thenReturn(List.of());

        // When
        AssessmentMetricsDto metrics = assessmentService.getMetrics(orgId);

        // Then — only the 3 assessments in org-1 are counted
        assertThat(metrics.getTotalCount()).isEqualTo(3L);
        assertThat(metrics.getDraftCount()).isEqualTo(2L);
        assertThat(metrics.getInProgressCount()).isEqualTo(1L);
        assertThat(metrics.getPastDueCount()).isEqualTo(0L);
    }

    @Test
    void testDetectConflicts_Found() {
        // Given
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(7);
        List<String> assessorIds = List.of(testUser.getId());

        Assessment conflictingAssessment = Assessment.builder()
                .id("conflict-1")
                .name("Conflicting Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .status("IN_PROGRESS")
                .assessorIds(assessorIds)
                .startDate(start.plusDays(2))
                .plannedEndDate(start.plusDays(5))
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findConflictingByAssessors(
                anyString(), eq(start), eq(end)))
                .thenReturn(List.of(conflictingAssessment));

        // When
        List<AssessmentDto> conflicts = assessmentService.detectConflicts(
                null, assessorIds, start, end);

        // Then
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getName()).isEqualTo("Conflicting Assessment");
    }

    @Test
    void testDetectConflicts_ExcludesCurrentAssessment() {
        // Given
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(7);
        List<String> assessorIds = List.of(testUser.getId());

        when(assessmentRepository.findConflictingByAssessors(
                anyString(), eq(start), eq(end)))
                .thenReturn(List.of(testAssessment));

        // When - passing same assessment ID should exclude it
        List<AssessmentDto> conflicts = assessmentService.detectConflicts(
                testAssessment.getId(), assessorIds, start, end);

        // Then
        assertThat(conflicts).isEmpty();
    }

    @Test
    void testDetectConflicts_NoConflicts() {
        // Given
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusDays(7);
        List<String> assessorIds = List.of(testUser.getId());

        when(assessmentRepository.findConflictingByAssessors(
                anyString(), eq(start), eq(end)))
                .thenReturn(List.of());

        // When
        List<AssessmentDto> conflicts = assessmentService.detectConflicts(
                null, assessorIds, start, end);

        // Then
        assertThat(conflicts).isEmpty();
    }

    @Test
    void testGetAssessmentsByDateRange_Success() {
        // Given
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(1);
        Pageable pageable = PageRequest.of(0, 10);

        Page<Assessment> assessmentPage = new PageImpl<>(
                List.of(testAssessment),
                pageable,
                1
        );

        when(assessmentRepository.findByDateRange(eq(start), eq(end), eq(pageable)))
                .thenReturn(assessmentPage);

        // When
        Page<AssessmentDto> result = assessmentService.getAssessmentsByDateRange(
                start, end, pageable);

        // Then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo(testAssessment.getName());
    }

    @Test
    void testExportToCsv_Success() {
        // Given
        List<AssessmentDto> assessments = List.of(
                AssessmentDto.fromEntity(testAssessment)
        );

        // When
        String csv = assessmentService.exportToCsv(assessments);

        // Then
        assertThat(csv).isNotBlank();
        assertThat(csv).contains("ID,Name,Status");
        assertThat(csv).contains(testAssessment.getId());
        assertThat(csv).contains(testAssessment.getName());
        assertThat(csv).contains("DRAFT");
    }

    @Test
    void testExportToCsv_HandlesSpecialCharacters() {
        // Given
        Assessment assessmentWithComma = Assessment.builder()
                .id("special-1")
                .name("Assessment, with comma")
                .applicationId("app, with comma")
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .status("DRAFT")
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdBy("user\"with\"quotes")
                .createdAt(LocalDateTime.now())
                .build();

        List<AssessmentDto> assessments = List.of(
                AssessmentDto.fromEntity(assessmentWithComma)
        );

        // When
        String csv = assessmentService.exportToCsv(assessments);

        // Then
        assertThat(csv).contains("\"Assessment, with comma\"");
        assertThat(csv).contains("\"app, with comma\"");
        assertThat(csv).contains("\"user\"\"with\"\"quotes\"");
    }

    @Test
    void testUpdateAssessment_Success() {
        // Given
        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .name("Updated Assessment")
                .status("IN_PROGRESS")
                .assessorIds(List.of(testUser.getId(), "user-2"))
                .remediationManagerId("user-3")
                .scope("Updated scope")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));

        Assessment updatedAssessment = Assessment.builder()
                .id(testAssessment.getId())
                .name("Updated Assessment")
                .applicationId(testAssessment.getApplicationId())
                .assessmentTypeId(testAssessment.getAssessmentTypeId())
                .organizationId(testAssessment.getOrganizationId())
                .reportTemplateId(testAssessment.getReportTemplateId())
                .reportTemplateVersion(testAssessment.getReportTemplateVersion())
                .status("IN_PROGRESS")
                .assessorIds(List.of(testUser.getId(), "user-2"))
                .remediationManagerId("user-3")
                .scope("Updated scope")
                .fieldDefinitions(testAssessment.getFieldDefinitions())
                .fieldValues(testAssessment.getFieldValues())
                .createdBy(testAssessment.getCreatedBy())
                .createdAt(testAssessment.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(updatedAssessment);

        // When
        AssessmentDto result = assessmentService.updateAssessment(
                testAssessment.getId(), request, "testuser");

        // Then
        assertThat(result.getName()).isEqualTo("Updated Assessment");
        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(result.getAssessorIds()).hasSize(2);
        assertThat(result.getRemediationManagerId()).isEqualTo("user-3");
        assertThat(result.getScope()).isEqualTo("Updated scope");

        verify(assessmentRepository).save(any(Assessment.class));
    }

    @Test
    void testUpdateAssessment_CompletionAnnouncedInApplicationChat() {
        // Given
        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("COMPLETED")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(List.of());
        when(assessmentChecklistRepository.findByAssessmentId(testAssessment.getId()))
                .thenReturn(List.of());

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then
        verify(applicationService).addSystemComment(
                eq(testApplication.getId()),
                contains("**Assessment completed**"),
                eq("testuser"));
    }

    @Test
    void testUpdateAssessment_NonCompletionStatusChangeNotAnnounced() {
        // Given
        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("IN_PROGRESS")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then
        verify(applicationService, never()).addSystemComment(any(), any(), any());
    }

    @Test
    void testUpdateAssessment_NotFound() {
        // Given
        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .name("Updated Assessment")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull("non-existent"))
                .thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assessmentService.updateAssessment(
                "non-existent", request, "testuser"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void testDeleteAssessment_Success() {
        // Given
        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));

        Assessment deletedAssessment = Assessment.builder()
                .id(testAssessment.getId())
                .name(testAssessment.getName())
                .applicationId(testAssessment.getApplicationId())
                .assessmentTypeId(testAssessment.getAssessmentTypeId())
                .organizationId(testAssessment.getOrganizationId())
                .reportTemplateId(testAssessment.getReportTemplateId())
                .status(testAssessment.getStatus())
                .fieldDefinitions(testAssessment.getFieldDefinitions())
                .fieldValues(testAssessment.getFieldValues())
                .createdAt(testAssessment.getCreatedAt())
                .deletedAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.save(any(Assessment.class)))
                .thenReturn(deletedAssessment);

        // When
        assessmentService.deleteAssessment(testAssessment.getId(), "testuser");

        // Then
        verify(assessmentRepository).save(argThat(assessment ->
                assessment.getDeletedAt() != null
        ));
    }

    @Test
    void testDeleteAssessment_NotFound() {
        // Given
        when(assessmentRepository.findByIdAndDeletedAtIsNull("non-existent"))
                .thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assessmentService.deleteAssessment(
                "non-existent", "testuser"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void testGetAssessment_Success() {
        // Given
        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));

        // When
        AssessmentDto result = assessmentService.getAssessment(testAssessment.getId());

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(testAssessment.getId());
        assertThat(result.getName()).isEqualTo(testAssessment.getName());
    }

    @Test
    void testGetAssessment_NotFound() {
        // Given
        when(assessmentRepository.findByIdAndDeletedAtIsNull("non-existent"))
                .thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> assessmentService.getAssessment("non-existent"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void testMigrateAssessorId_FromSingleToList() {
        // Given - Assessment with legacy assessorId field
        Assessment legacyAssessment = Assessment.builder()
                .id("legacy-1")
                .name("Legacy Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .status("DRAFT")
                .assessorId(testUser.getId())
                .assessorIds(null) // Legacy has null list
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(legacyAssessment.getId()))
                .thenReturn(Optional.of(legacyAssessment));
        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(userRepository.findById(testUser.getId()))
                .thenReturn(Optional.of(testUser));

        // When
        AssessmentDto result = assessmentService.getAssessment(legacyAssessment.getId());

        // Then
        // getAssessment now performs migration and enrichment
        // Legacy assessorId is migrated to assessorIds array
        assertThat(result.getAssessorId()).isEqualTo(testUser.getId());
        assertThat(result.getAssessorIds()).containsExactly(testUser.getId()); // Migration performed
        assertThat(result.getApplicationName()).isEqualTo(testApplication.getName()); // Enriched
        assertThat(result.getAssessmentTypeName()).isEqualTo(testAssessmentType.getName()); // Enriched
    }

    @Test
    void testGetAssessment_EnrichesAppId() {
        // Given — the app resolution the Create/Edit Assessment page relies on to
        // populate Application Id/Name without a separate applications:read call
        testApplication.setAppId("ASMT-1");
        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));

        // When
        AssessmentDto result = assessmentService.getAssessment(testAssessment.getId());

        // Then
        assertThat(result.getAppId()).isEqualTo("ASMT-1");
        assertThat(result.getApplicationName()).isEqualTo(testApplication.getName());
    }

    @Test
    void testIsPastDue_Calculation() {
        // Given - Past due assessment
        Assessment pastDueAssessment = Assessment.builder()
                .id("pastdue-1")
                .name("Past Due Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .status("IN_PROGRESS")
                .plannedEndDate(LocalDateTime.now().minusDays(1))
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(pastDueAssessment.getId()))
                .thenReturn(Optional.of(pastDueAssessment));

        // When
        AssessmentDto result = assessmentService.getAssessment(pastDueAssessment.getId());

        // Then
        assertThat(result.getIsPastDue()).isTrue();
    }

    @Test
    void testIsPastDue_FutureDate() {
        // Given - Future assessment
        Assessment futureAssessment = Assessment.builder()
                .id("future-1")
                .name("Future Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .status("IN_PROGRESS")
                .plannedEndDate(LocalDateTime.now().plusDays(7))
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(futureAssessment.getId()))
                .thenReturn(Optional.of(futureAssessment));

        // When
        AssessmentDto result = assessmentService.getAssessment(futureAssessment.getId());

        // Then
        assertThat(result.getIsPastDue()).isFalse();
    }

    // ── FieldScope tests ────────────────────────────────────────────────────

    @Test
    void testUserDefinedField_DefaultScopeIsAssessment() {
        // When - build without specifying scope
        UserDefinedField field = UserDefinedField.builder()
                .id("field-1")
                .variableName("executive_summary")
                .displayName("Executive Summary")
                .fieldType(FieldType.STRING)
                .build();

        // Then - scope should default to ASSESSMENT
        assertThat(field.getFieldScope()).isEqualTo(FieldScope.ASSESSMENT);
    }

    @Test
    void testUserDefinedField_VulnerabilityScopeCanBeSet() {
        // When - explicitly set scope to VULNERABILITY
        UserDefinedField field = UserDefinedField.builder()
                .id("field-2")
                .variableName("cvss_notes")
                .displayName("CVSS Notes")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.VULNERABILITY)
                .build();

        // Then
        assertThat(field.getFieldScope()).isEqualTo(FieldScope.VULNERABILITY);
    }

    @Test
    void testCreateAssessment_OnlySnapshotsAssessmentScopedFields() {
        // Given - template with one ASSESSMENT field and one VULNERABILITY field
        UserDefinedField assessmentField = UserDefinedField.builder()
                .id("field-1")
                .variableName("executive_summary")
                .displayName("Executive Summary")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.ASSESSMENT)
                .build();

        UserDefinedField vulnerabilityField = UserDefinedField.builder()
                .id("field-2")
                .variableName("cvss_justification")
                .displayName("CVSS Justification")
                .fieldType(FieldType.STRING)
                .fieldScope(FieldScope.VULNERABILITY)
                .build();

        ReportTemplate mixedTemplate = ReportTemplate.builder()
                .id("template-mixed")
                .name("Mixed Fields Template")
                .assessmentTypeId(testAssessmentType.getId())
                .version(1)
                .active(true)
                .userDefinedFields(List.of(assessmentField, vulnerabilityField))
                .createdAt(LocalDateTime.now())
                .build();

        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(mixedTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(mixedTemplate.getId()))
                .thenReturn(Optional.of(mixedTemplate));

        ArgumentCaptor<Assessment> savedCaptor = ArgumentCaptor.forClass(Assessment.class);
        when(assessmentRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.createAssessment(request, "testuser");

        // Then - snapshot must contain only the ASSESSMENT-scoped field
        Assessment saved = savedCaptor.getValue();
        assertThat(saved.getFieldDefinitions()).hasSize(1);
        assertThat(saved.getFieldDefinitions().get(0).getVariableName()).isEqualTo("executive_summary");
        assertThat(saved.getFieldDefinitions().get(0).getFieldScope()).isEqualTo(FieldScope.ASSESSMENT);
    }

    @Test
    void testCreateAssessment_ExcludesVulnerabilityScopedFieldsFromSnapshot() {
        // Given - template with only VULNERABILITY-scoped fields
        UserDefinedField vulnField = UserDefinedField.builder()
                .id("field-vuln")
                .variableName("proof_of_concept")
                .displayName("Proof of Concept")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.VULNERABILITY)
                .build();

        ReportTemplate vulnOnlyTemplate = ReportTemplate.builder()
                .id("template-vuln")
                .name("Vulnerability-Only Template")
                .assessmentTypeId(testAssessmentType.getId())
                .version(1)
                .active(true)
                .userDefinedFields(List.of(vulnField))
                .createdAt(LocalDateTime.now())
                .build();

        CreateAssessmentRequest request = CreateAssessmentRequest.builder()
                .name("New Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .reportTemplateId(vulnOnlyTemplate.getId())
                .initialFieldValues(new HashMap<>())
                .build();

        when(applicationRepository.findById(testApplication.getId()))
                .thenReturn(Optional.of(testApplication));
        when(assessmentTypeRepository.findById(testAssessmentType.getId()))
                .thenReturn(Optional.of(testAssessmentType));
        when(reportTemplateRepository.findById(vulnOnlyTemplate.getId()))
                .thenReturn(Optional.of(vulnOnlyTemplate));

        ArgumentCaptor<Assessment> savedCaptor = ArgumentCaptor.forClass(Assessment.class);
        when(assessmentRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.createAssessment(request, "testuser");

        // Then - snapshot must be empty; VULNERABILITY fields are not snapshotted
        Assessment saved = savedCaptor.getValue();
        assertThat(saved.getFieldDefinitions()).isEmpty();
    }

    @Test
    void testSyncFieldDefinitions_OnlyIncludesAssessmentScopedFields() {
        // Given - assessment is behind template version; template has mixed-scope fields
        UserDefinedField assessmentField = UserDefinedField.builder()
                .id("field-1")
                .variableName("executive_summary")
                .displayName("Executive Summary")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.ASSESSMENT)
                .build();

        UserDefinedField vulnerabilityField = UserDefinedField.builder()
                .id("field-2")
                .variableName("cvss_justification")
                .displayName("CVSS Justification")
                .fieldType(FieldType.STRING)
                .fieldScope(FieldScope.VULNERABILITY)
                .build();

        ReportTemplate updatedTemplate = ReportTemplate.builder()
                .id("template-1")
                .name("Updated Template")
                .assessmentTypeId(testAssessmentType.getId())
                .version(2) // newer than assessment version
                .active(true)
                .userDefinedFields(List.of(assessmentField, vulnerabilityField))
                .createdAt(LocalDateTime.now())
                .build();

        Assessment staleAssessment = Assessment.builder()
                .id("stale-1")
                .name("Stale Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(updatedTemplate.getId())
                .reportTemplateVersion(1) // behind template
                .templateName(updatedTemplate.getName())
                .status("IN_PROGRESS")
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(staleAssessment.getId()))
                .thenReturn(Optional.of(staleAssessment));
        when(reportTemplateRepository.findById(updatedTemplate.getId()))
                .thenReturn(Optional.of(updatedTemplate));

        ArgumentCaptor<Assessment> savedCaptor = ArgumentCaptor.forClass(Assessment.class);
        when(assessmentRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When - getAssessment triggers syncFieldDefinitionsIfNeeded
        assessmentService.getAssessment(staleAssessment.getId());

        // Then - only ASSESSMENT-scoped field synced; VULNERABILITY field excluded
        Assessment synced = savedCaptor.getValue();
        assertThat(synced.getFieldDefinitions()).hasSize(1);
        assertThat(synced.getFieldDefinitions().get(0).getVariableName()).isEqualTo("executive_summary");
        assertThat(synced.getFieldDefinitions().get(0).getFieldScope()).isEqualTo(FieldScope.ASSESSMENT);
        assertThat(synced.getReportTemplateVersion()).isEqualTo(2);
    }

    @Test
    void testGetAssessment_backfillsTemplateFileFromLiveTemplate() {
        // Given - assessment snapshotted before a DOCX was uploaded to the template
        ReportTemplate templateWithFile = ReportTemplate.builder()
                .id("template-1")
                .name("Template With File")
                .assessmentTypeId(testAssessmentType.getId())
                .version(1)
                .active(true)
                .templateFileId("report-templates/template-1/report.docx")
                .css("h1 { color: red; }")
                .font("Georgia")
                .userDefinedFields(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();

        Assessment noFileAssessment = Assessment.builder()
                .id("nofile-1")
                .name("Assessment Without File Snapshot")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(templateWithFile.getId())
                .reportTemplateVersion(1) // same version — only the file sync should fire
                .templateFileId(null)
                .status("IN_PROGRESS")
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(noFileAssessment.getId()))
                .thenReturn(Optional.of(noFileAssessment));
        when(reportTemplateRepository.findById(templateWithFile.getId()))
                .thenReturn(Optional.of(templateWithFile));

        ArgumentCaptor<Assessment> savedCaptor = ArgumentCaptor.forClass(Assessment.class);
        when(assessmentRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.getAssessment(noFileAssessment.getId());

        // Then - the live template's file id and css are synced onto the assessment
        Assessment synced = savedCaptor.getValue();
        assertThat(synced.getTemplateFileId()).isEqualTo("report-templates/template-1/report.docx");
        assertThat(synced.getTemplateCss()).isEqualTo("h1 { color: red; }");
        assertThat(synced.getTemplateFont()).isEqualTo("Georgia");
    }

    @Test
    void testIsPastDue_CompletedStatus() {
        // Given - Completed but past end date
        Assessment completedAssessment = Assessment.builder()
                .id("completed-1")
                .name("Completed Assessment")
                .applicationId(testApplication.getId())
                .assessmentTypeId(testAssessmentType.getId())
                .organizationId(testOrganization.getId())
                .reportTemplateId(testTemplate.getId())
                .status("COMPLETED")
                .plannedEndDate(LocalDateTime.now().minusDays(1))
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(completedAssessment.getId()))
                .thenReturn(Optional.of(completedAssessment));

        // When
        AssessmentDto result = assessmentService.getAssessment(completedAssessment.getId());

        // Then
        assertThat(result.getIsPastDue()).isFalse();
    }

    @Test
    void testUpdateAssessment_SetsPeerReviewedAtWhenStatusChangesToPendingReview() {
        // Given
        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("PENDING_REVIEW")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));

        ArgumentCaptor<Assessment> savedCaptor = ArgumentCaptor.forClass(Assessment.class);
        when(assessmentRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then - peerReviewedAt must be set
        Assessment saved = savedCaptor.getValue();
        assertThat(saved.getPeerReviewedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void testUpdateAssessment_DoesNotOverwritePeerReviewedAtOnRepeatedTransition() {
        // Given - already in PENDING_REVIEW with a prior timestamp
        LocalDateTime originalPeerReviewedAt = LocalDateTime.now().minusHours(2);
        Assessment alreadyPendingAssessment = Assessment.builder()
                .id(testAssessment.getId())
                .name(testAssessment.getName())
                .applicationId(testAssessment.getApplicationId())
                .assessmentTypeId(testAssessment.getAssessmentTypeId())
                .organizationId(testAssessment.getOrganizationId())
                .reportTemplateId(testAssessment.getReportTemplateId())
                .reportTemplateVersion(testAssessment.getReportTemplateVersion())
                .status("PENDING_REVIEW")
                .peerReviewedAt(originalPeerReviewedAt)
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(testAssessment.getCreatedAt())
                .build();

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("PENDING_REVIEW")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(alreadyPendingAssessment));

        ArgumentCaptor<Assessment> savedCaptor = ArgumentCaptor.forClass(Assessment.class);
        when(assessmentRepository.save(savedCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then - peerReviewedAt must NOT be overwritten
        Assessment saved = savedCaptor.getValue();
        assertThat(saved.getPeerReviewedAt()).isEqualTo(originalPeerReviewedAt);
    }

    @Test
    void testUpdateAssessment_SetsOpenedAtOnVulnsWhenFinalized() {
        // Given - assessment transitions from DRAFT to COMPLETED
        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("COMPLETED")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(assessmentChecklistRepository.findByAssessmentId(testAssessment.getId()))
                .thenReturn(List.of());

        Vulnerability v1 = Vulnerability.builder().id("v-1").assessmentId(testAssessment.getId())
                .name("SQLi").severity(VulnerabilitySeverity.CRITICAL).build();
        Vulnerability v2 = Vulnerability.builder().id("v-2").assessmentId(testAssessment.getId())
                .name("XSS").severity(VulnerabilitySeverity.HIGH).openedAt(LocalDateTime.now().minusDays(1)).build();

        when(vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(List.of(v1, v2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Vulnerability>> savedVulnsCaptor = ArgumentCaptor.forClass(List.class);
        when(vulnerabilityRepository.saveAll(savedVulnsCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then - v1 (no openedAt) must now have it set; v2 must be unchanged
        List<Vulnerability> saved = savedVulnsCaptor.getValue();
        Vulnerability savedV1 = saved.stream().filter(v -> v.getId().equals("v-1")).findFirst().orElseThrow();
        Vulnerability savedV2 = saved.stream().filter(v -> v.getId().equals("v-2")).findFirst().orElseThrow();
        assertThat(savedV1.getOpenedAt()).isNotNull();
        assertThat(savedV2.getOpenedAt()).isEqualTo(v2.getOpenedAt());
    }

    @Test
    void testUpdateAssessment_SeedsRemediationOwnerFromTheRemediationManagerOnFinalize() {
        // The person picked as remediation manager when the assessment was scheduled becomes
        // each newly opened finding's remediation owner, and joins its discussion.
        testAssessment.setRemediationManagerId("user-rem");

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("COMPLETED")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(testAssessment));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(assessmentChecklistRepository.findByAssessmentId(testAssessment.getId()))
                .thenReturn(List.of());

        User manager = new User();
        manager.setId("user-rem");
        manager.setUsername("rmanager");
        when(userRepository.findById("user-rem")).thenReturn(Optional.of(manager));

        Vulnerability fresh = Vulnerability.builder().id("v-1").assessmentId(testAssessment.getId())
                .name("SQLi").severity(VulnerabilitySeverity.CRITICAL)
                .subscribers(new ArrayList<>()).build();
        // Already open and already reassigned — a later finalization must not overwrite that.
        Vulnerability reassigned = Vulnerability.builder().id("v-2").assessmentId(testAssessment.getId())
                .name("XSS").severity(VulnerabilitySeverity.HIGH)
                .openedAt(LocalDateTime.now().minusDays(1))
                .remediationOwnerId("someone-else")
                .subscribers(new ArrayList<>()).build();

        when(vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(List.of(fresh, reassigned));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Vulnerability>> savedVulnsCaptor = ArgumentCaptor.forClass(List.class);
        when(vulnerabilityRepository.saveAll(savedVulnsCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        List<Vulnerability> saved = savedVulnsCaptor.getValue();
        Vulnerability savedFresh = saved.stream().filter(v -> v.getId().equals("v-1")).findFirst().orElseThrow();
        Vulnerability savedReassigned = saved.stream().filter(v -> v.getId().equals("v-2")).findFirst().orElseThrow();

        assertThat(savedFresh.getRemediationOwnerId()).isEqualTo("user-rem");
        assertThat(savedFresh.getSubscribers()).containsExactly("rmanager");
        assertThat(savedReassigned.getRemediationOwnerId()).isEqualTo("someone-else");
    }

    @Test
    void testUpdateAssessment_DoesNotSetOpenedAtWhenAlreadyCompleted() {
        // Given - assessment is already COMPLETED, status stays COMPLETED
        Assessment alreadyCompleted = Assessment.builder()
                .id(testAssessment.getId())
                .name(testAssessment.getName())
                .applicationId(testAssessment.getApplicationId())
                .assessmentTypeId(testAssessment.getAssessmentTypeId())
                .organizationId(testAssessment.getOrganizationId())
                .reportTemplateId(testAssessment.getReportTemplateId())
                .reportTemplateVersion(testAssessment.getReportTemplateVersion())
                .status("COMPLETED")
                .completedDate(LocalDateTime.now().minusDays(1))
                .fieldDefinitions(new ArrayList<>())
                .fieldValues(new HashMap<>())
                .createdAt(testAssessment.getCreatedAt())
                .build();

        UpdateAssessmentRequest request = UpdateAssessmentRequest.builder()
                .status("COMPLETED")
                .build();

        when(assessmentRepository.findByIdAndDeletedAtIsNull(testAssessment.getId()))
                .thenReturn(Optional.of(alreadyCompleted));
        when(assessmentRepository.save(any(Assessment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        assessmentService.updateAssessment(testAssessment.getId(), request, "testuser");

        // Then - vulnerabilityRepository.saveAll should NOT have been called
        verify(vulnerabilityRepository, never()).saveAll(any());
    }

    @Test
    void testGetAssessment_IncludesVulnerabilitySummary() {
        // Given
        String assessmentId = testAssessment.getId();
        when(assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId))
                .thenReturn(Optional.of(testAssessment));
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.CRITICAL))
                .thenReturn(2L);
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.HIGH))
                .thenReturn(5L);
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.MEDIUM))
                .thenReturn(3L);
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.LOW))
                .thenReturn(1L);
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.INFORMATIONAL))
                .thenReturn(0L);

        // When
        AssessmentDto result = assessmentService.getAssessment(assessmentId);

        // Then
        assertThat(result.getVulnerabilitySummary()).isNotNull();
        assertThat(result.getVulnerabilitySummary().getCritical()).isEqualTo(2L);
        assertThat(result.getVulnerabilitySummary().getHigh()).isEqualTo(5L);
        assertThat(result.getVulnerabilitySummary().getMedium()).isEqualTo(3L);
        assertThat(result.getVulnerabilitySummary().getLow()).isEqualTo(1L);
        assertThat(result.getVulnerabilitySummary().getInformational()).isEqualTo(0L);
    }

    @Test
    void testGetAssessment_VulnerabilitySummaryCountsUnopenedFindings() {
        // Given - unfinalized assessment: findings exist but none have been opened.
        // The summary reflects what's been found so far, not just opened vulns.
        String assessmentId = testAssessment.getId();
        when(assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId))
                .thenReturn(Optional.of(testAssessment));
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(eq(assessmentId), any(VulnerabilitySeverity.class)))
                .thenReturn(0L);
        when(vulnerabilityRepository.countByAssessmentIdAndSeverityAndDeletedAtIsNull(assessmentId, VulnerabilitySeverity.HIGH))
                .thenReturn(4L);

        // When
        AssessmentDto result = assessmentService.getAssessment(assessmentId);

        // Then - unopened findings are counted; the opened-only count is never used
        assertThat(result.getVulnerabilitySummary()).isNotNull();
        assertThat(result.getVulnerabilitySummary().getHigh()).isEqualTo(4L);
        verify(vulnerabilityRepository, never())
                .countByAssessmentIdAndSeverityAndOpenedAtIsNotNullAndDeletedAtIsNull(anyString(), any(VulnerabilitySeverity.class));
    }

    @Test
    void searchAssessmentsAdvanced_orgScopedUserWithNoResolvableOrg_failsClosed() {
        // Org-scoped caller whose user record resolves to no org must see nothing,
        // not fall through to an unscoped (all-orgs) query.
        var auth = new UsernamePasswordAuthenticationToken("ghost", null,
                List.of(new SimpleGrantedAuthority(Permission.ASSESSMENTS_READ_ORG.getPermission())));
        when(accessScopeService.resolveAssessmentScope(auth)).thenReturn(
                new AccessScopeService.AssessmentScope(
                        AccessScopeService.AssessmentScopeKind.DENIED, null, null, null, null));

        var result = assessmentService.searchAssessmentsAdvanced(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, PageRequest.of(0, 20), auth);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(assessmentRepository, never()).searchAdvanced(any(), any());
    }

    @Test
    void searchAssessmentsAdvanced_orgScopedUser_isForcedToOwnOrg() {
        // An org-scoped caller who passes a different organizationId must still be scoped to
        // their own org — they can't query another org's assessments.
        var auth = new UsernamePasswordAuthenticationToken("org-user", null,
                List.of(new SimpleGrantedAuthority(Permission.ASSESSMENTS_READ_ORG.getPermission())));
        when(accessScopeService.resolveAssessmentScope(auth)).thenReturn(
                new AccessScopeService.AssessmentScope(
                        AccessScopeService.AssessmentScopeKind.ORG, "org-A", null, null, null));
        when(assessmentRepository.searchAdvanced(any(), any())).thenReturn(Page.empty());

        assessmentService.searchAssessmentsAdvanced(
                null, null, null, "org-B", null, null, null, null, null, null,
                null, null, null, null, null, PageRequest.of(0, 20), auth);

        var captor = ArgumentCaptor.forClass(AssessmentSearchCriteria.class);
        verify(assessmentRepository).searchAdvanced(captor.capture(), any());
        assertThat(captor.getValue().organizationId()).isEqualTo("org-A"); // forced to own org, not "org-B"
    }
}
