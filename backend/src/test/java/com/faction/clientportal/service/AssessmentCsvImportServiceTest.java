package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.AssessmentImportPreviewDto;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import com.faction.clientportal.service.email.EventNotificationEmailSender;
import com.faction.clientportal.service.extension.ExtensionEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assessment CSV import: case-insensitive resolution, pending applications and campaigns,
 * custom fields resolved against each row's template, the all-or-nothing commit, and the
 * notify switch.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentCsvImportServiceTest extends TestContainersConfig {

    @Autowired private AssessmentCsvImportService service;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentTypeRepository assessmentTypeRepository;
    @Autowired private ReportTemplateRepository reportTemplateRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private UserRepository userRepository;

    @MockBean private EventNotificationEmailSender eventEmailSender;
    @MockBean private ExtensionEventService extensionEventService;
    @MockBean private NotificationService notificationService;

    private AssessmentType pentest;
    private ReportTemplate pentestTemplate;
    private Application checkout;
    private User jane;
    private User sam;
    private Team redTeam;
    private Campaign defaultCampaign;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(eventEmailSender, extensionEventService, notificationService);
        assessmentRepository.deleteAll();
        reportTemplateRepository.deleteAll();
        assessmentTypeRepository.deleteAll();
        applicationRepository.deleteAll();
        campaignRepository.deleteAll();
        teamRepository.deleteAll();
        userRepository.deleteAll();

        pentest = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Penetration Test").description("Pentest").createdAt(LocalDateTime.now()).build());
        pentestTemplate = reportTemplateRepository.save(ReportTemplate.builder()
                .name("Pentest Report").assessmentTypeId(pentest.getId()).version(1).active(true)
                .userDefinedFields(new ArrayList<>(List.of(
                        UserDefinedField.builder().id("f-env").variableName("environment")
                                .displayName("Environment").fieldType(FieldType.DROPDOWN)
                                .dropdownOptions(new ArrayList<>(List.of("Production", "Staging")))
                                .fieldScope(FieldScope.ASSESSMENT).build(),
                        UserDefinedField.builder().id("f-ticket").variableName("ticket")
                                .displayName("Ticket").fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.ASSESSMENT).build())))
                .createdAt(LocalDateTime.now()).build());
        checkout = applicationRepository.save(Application.builder()
                .appId("APP-001").name("Checkout").createdAt(LocalDateTime.now()).build());
        jane = userRepository.save(User.builder().username("jane.doe").email("jane@example.com")
                .firstName("Jane").lastName("Doe").password("x").loginOption(LoginOption.NATIVE)
                .isInternal(true).createdAt(LocalDateTime.now()).build());
        sam = userRepository.save(User.builder().username("sam.lee").email("Sam.Lee@Example.com")
                .firstName("Sam").lastName("Lee").password("x").loginOption(LoginOption.NATIVE)
                .isInternal(true).createdAt(LocalDateTime.now()).build());
        redTeam = teamRepository.save(Team.builder().name("Red Team").createdAt(LocalDateTime.now()).build());
        defaultCampaign = campaignRepository.save(Campaign.builder().name("General").isDefault(true)
                .createdAt(LocalDateTime.now()).build());
    }

    private MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "assessments.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** What most tests import as: allowed to create campaigns. */
    private static final Set<String> CAN_CREATE_CAMPAIGNS = Set.of("assessments:create:all", "campaigns:create:all");

    private AssessmentImportPreviewDto preview(String body) throws IOException {
        return service.preview(csv(body), CAN_CREATE_CAMPAIGNS);
    }

    // ── Preview ────────────────────────────────────────────────────────────

    @Test
    void previewResolvesEveryColumnIgnoringCase() throws IOException {
        var p = preview("""
                NAME,appid,AssessmentType,startDate,endDate,assessors,campaign,team,engagementManager,Environment
                Q4 Pentest,app-001,penetration test,2026-10-05,2026-10-16,JANE.DOE;sam.lee@example.com,general,red team,SAM.LEE,Production
                """);

        assertThat(p.isValid()).isTrue();
        assertThat(p.getTotal()).isEqualTo(1);
        var row = p.getRows().get(0);
        assertThat(row.getErrors()).isEmpty();
        assertThat(row.getLine()).isEqualTo(2);
        assertThat(row.getApplication()).isEqualTo("Checkout (APP-001)");
        assertThat(row.isNewApplication()).isFalse();
        assertThat(row.getAssessmentType()).isEqualTo("Penetration Test");
        assertThat(row.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(row.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(row.getAssessors()).containsExactly("Jane Doe", "Sam Lee");
        assertThat(row.getCampaign()).isEqualTo("General");
        assertThat(row.isNewCampaign()).isFalse();
        assertThat(row.getTeam()).isEqualTo("Red Team");
    }

    @Test
    void previewWritesNothing() throws IOException {
        preview("""
                name,appId,applicationName,assessmentType,startDate,durationDays,campaign
                New One,APP-NEW,Brand New,Penetration Test,2026-10-05,5,Brand New Campaign
                """);

        assertThat(assessmentRepository.count()).isZero();
        assertThat(applicationRepository.findAllByAppIdIgnoreCase("APP-NEW")).isEmpty();
        assertThat(campaignRepository.findAllByNameIgnoreCase("Brand New Campaign")).isEmpty();
    }

    @Test
    void unknownAppAndCampaignBecomePendingAndAreSharedAcrossRows() throws IOException {
        var p = preview("""
                name,appId,applicationName,assessmentType,startDate,durationDays,campaign
                One,APP-NEW,Brand New,Penetration Test,2026-10-05,5,Q4 Campaign
                Two,app-new,Other Spelling,Penetration Test,2026-11-05,5,q4 campaign
                """);

        assertThat(p.isValid()).isTrue();
        assertThat(p.getNewApplicationCount()).isEqualTo(1);
        assertThat(p.getNewCampaignCount()).isEqualTo(1);
        assertThat(p.getRows()).allSatisfy(r -> {
            assertThat(r.isNewApplication()).isTrue();
            assertThat(r.isNewCampaign()).isTrue();
            assertThat(r.getApplication()).isEqualTo("Brand New (APP-NEW)");
            assertThat(r.getCampaign()).isEqualTo("Q4 Campaign");
        });
    }

    @Test
    void blankCampaignUsesTheDefaultCampaign() throws IOException {
        var row = preview("""
                name,appId,assessmentType,startDate,durationDays
                One,APP-001,Penetration Test,2026-10-05,5
                """).getRows().get(0);

        assertThat(row.getCampaign()).isEqualTo("General");
        assertThat(row.isNewCampaign()).isFalse();
    }

    @Test
    void durationDaysComputesTheEndDateAndEndDateWins() throws IOException {
        var p = preview("""
                name,appId,assessmentType,startDate,endDate,durationDays
                By Duration,APP-001,Penetration Test,2026-10-05,,5
                Both Given,APP-001,Penetration Test,2026-10-05,2026-10-30,5
                """);

        assertThat(p.getRows().get(0).getEndDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(p.getRows().get(1).getEndDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void rowErrorsAreCollectedPerRow() throws IOException {
        var p = preview("""
                name,appId,assessmentType,startDate,endDate,assessors,team,reportTemplate
                Good,APP-001,Penetration Test,2026-10-05,2026-10-10,jane.doe,,
                ,APP-001,Nope Type,05/10/2026,2026-10-10,ghost;nobody@example.com,Blue Team,Missing Template
                Backwards,APP-001,Penetration Test,2026-10-10,2026-10-05,,,
                """);

        assertThat(p.isValid()).isFalse();
        assertThat(p.getValidCount()).isEqualTo(1);
        assertThat(p.getErrorCount()).isEqualTo(2);
        assertThat(p.getRows().get(1).getErrors()).containsExactlyInAnyOrder(
                "name is required",
                "Unknown assessment type 'Nope Type'",
                "startDate '05/10/2026' is not a YYYY-MM-DD date",
                "Unknown user 'ghost'",
                "Unknown user 'nobody@example.com'",
                "Unknown team 'Blue Team'",
                "Unknown report template 'Missing Template'");
        assertThat(p.getRows().get(2).getErrors()).containsExactly("endDate is before startDate");
    }

    @Test
    void ambiguousCaseInsensitiveMatchIsAnError() throws IOException {
        campaignRepository.save(Campaign.builder().name("Q1").createdAt(LocalDateTime.now()).build());
        campaignRepository.save(Campaign.builder().name("q1").createdAt(LocalDateTime.now()).build());

        var row = preview("""
                name,appId,assessmentType,startDate,durationDays,campaign
                One,APP-001,Penetration Test,2026-10-05,5,Q1
                """).getRows().get(0);

        assertThat(row.getErrors()).containsExactly("Ambiguous campaign 'Q1': 2 match ignoring case");
    }

    @Test
    void deletedUsersDoNotMatch() throws IOException {
        sam.setDeletedAt(LocalDateTime.now());
        userRepository.save(sam);

        var row = preview("""
                name,appId,assessmentType,startDate,durationDays,assessors
                One,APP-001,Penetration Test,2026-10-05,5,sam.lee
                """).getRows().get(0);

        assertThat(row.getErrors()).containsExactly("Unknown user 'sam.lee'");
    }

    @Test
    void customFieldsAreCheckedAgainstTheRowsTemplate() throws IOException {
        var p = preview("""
                name,appId,assessmentType,startDate,durationDays,environment,ticket
                Good,APP-001,Penetration Test,2026-10-05,5,Staging,SEC-1
                Bad Option,APP-001,Penetration Test,2026-10-05,5,Moon,
                """);

        assertThat(p.getRows().get(0).getErrors()).isEmpty();
        assertThat(p.getRows().get(1).getErrors()).hasSize(1);
        assertThat(p.getRows().get(1).getErrors().get(0)).contains("Environment");
    }

    @Test
    void customFieldNotOnTheRowsTemplateIsAnError() throws IOException {
        AssessmentType webApp = assessmentTypeRepository.save(AssessmentType.builder()
                .name("Web App").createdAt(LocalDateTime.now()).build());
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Web Report").assessmentTypeId(webApp.getId()).version(1).active(true)
                .userDefinedFields(new ArrayList<>()).createdAt(LocalDateTime.now()).build());

        var row = preview("""
                name,appId,assessmentType,startDate,durationDays,ticket
                One,APP-001,Web App,2026-10-05,5,SEC-9
                """).getRows().get(0);

        assertThat(row.getErrors())
                .containsExactly("Custom field 'ticket' is not on report template 'Web Report'");
    }

    @Test
    void wholeFileProblemsAreRejected() {
        assertThatThrownBy(() -> preview(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No CSV file");
        assertThatThrownBy(() -> preview("\n\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> preview("name,appId,assessmentType,startDate,durationDays\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no assessments");
        assertThatThrownBy(() -> preview("name,appId,assessmentType,startDate,durationDays,widgets\nx,y,z,2026-01-01,1,3\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("widgets");
        assertThatThrownBy(() -> preview("name,appId,startDate,durationDays\nx,y,2026-01-01,1\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assessmentType");
        assertThatThrownBy(() -> preview("name,appId,assessmentType,startDate\nx,y,z,2026-01-01\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endDate");
        assertThatThrownBy(() -> preview("name,assessmentType,startDate,durationDays\nx,z,2026-01-01,1\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("appId");
        assertThatThrownBy(() -> service.preview(null, CAN_CREATE_CAMPAIGNS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void templateListsBuiltInColumnsThenCustomFields() {
        String template = service.template();
        String header = template.lines().findFirst().orElseThrow();

        assertThat(header).startsWith("name,appId,applicationName,assessmentType,startDate,endDate,durationDays,"
                + "assessors,campaign,team,engagementManager,remediationManager,reportTemplate,scope");
        assertThat(header).endsWith(",environment,ticket");
        assertThat(template.lines().count()).isEqualTo(2);
    }

    // ── Import ─────────────────────────────────────────────────────────────

    @Autowired private AssessmentImportCommitter committer;

    private com.faction.clientportal.dto.AssessmentImportResultDto importCsv(String body, boolean notify)
            throws IOException {
        return service.importCsv(csv(body), notify, "importer", CAN_CREATE_CAMPAIGNS);
    }

    @Test
    void importCreatesEveryRowWithItsResolvedValues() throws IOException {
        var result = importCsv("""
                name,appId,assessmentType,startDate,endDate,assessors,team,remediationManager,scope,environment
                Q4 Pentest,app-001,penetration test,2026-10-05,2026-10-16,jane.doe;SAM.LEE,red team,jane@example.com,Web <b>and</b> API,Staging
                """, false);

        assertThat(result.getCreated()).isEqualTo(1);
        Assessment a = assessmentRepository.findById(result.getAssessmentIds().get(0)).orElseThrow();
        assertThat(a.getName()).isEqualTo("Q4 Pentest");
        assertThat(a.getApplicationId()).isEqualTo(checkout.getId());
        assertThat(a.getAssessmentTypeId()).isEqualTo(pentest.getId());
        assertThat(a.getReportTemplateId()).isEqualTo(pentestTemplate.getId());
        assertThat(a.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 5).atStartOfDay());
        assertThat(a.getPlannedEndDate()).isEqualTo(LocalDate.of(2026, 10, 16).atStartOfDay());
        assertThat(a.getAssessorIds()).containsExactly(jane.getId(), sam.getId());
        assertThat(a.getTeamId()).isEqualTo(redTeam.getId());
        assertThat(a.getRemediationManagerId()).isEqualTo(jane.getId());
        assertThat(a.getCampaignId()).isEqualTo(defaultCampaign.getId());
        assertThat(a.getScope()).isEqualTo("<p>Web &lt;b&gt;and&lt;/b&gt; API</p>");
        assertThat(a.getFieldValues()).containsEntry("f-env", "Staging");
    }

    @Test
    void importCreatesPendingApplicationsAndCampaignsOnce() throws IOException {
        var result = importCsv("""
                name,appId,applicationName,assessmentType,startDate,durationDays,campaign
                One,APP-NEW,Brand New,Penetration Test,2026-10-05,5,Q4 Campaign
                Two,app-new,Ignored Spelling,Penetration Test,2026-11-05,5,q4 CAMPAIGN
                """, false);

        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(result.getCreatedApplications()).containsExactly("Brand New");
        assertThat(result.getCreatedCampaigns()).containsExactly("Q4 Campaign");
        List<Application> apps = applicationRepository.findAllByAppIdIgnoreCase("APP-NEW");
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).getName()).isEqualTo("Brand New");
        List<Campaign> campaigns = campaignRepository.findAllByNameIgnoreCase("q4 campaign");
        assertThat(campaigns).hasSize(1);
        assertThat(assessmentRepository.findAll())
                .allSatisfy(a -> {
                    assertThat(a.getApplicationId()).isEqualTo(apps.get(0).getId());
                    assertThat(a.getCampaignId()).isEqualTo(campaigns.get(0).getId());
                });
    }

    @Test
    void anyRowErrorImportsNothing() {
        assertThatThrownBy(() -> importCsv("""
                name,appId,applicationName,assessmentType,startDate,durationDays,campaign
                Good,APP-NEW,Brand New,Penetration Test,2026-10-05,5,New Campaign
                Bad,APP-001,,No Such Type,2026-10-05,5,
                """, false))
                .isInstanceOf(com.faction.clientportal.exception.AssessmentImportInvalidException.class)
                .satisfies(e -> assertThat(((com.faction.clientportal.exception.AssessmentImportInvalidException) e)
                        .getPreview().getErrorCount()).isEqualTo(1));

        assertThat(assessmentRepository.count()).isZero();
        assertThat(applicationRepository.findAllByAppIdIgnoreCase("APP-NEW")).isEmpty();
        assertThat(campaignRepository.findAllByNameIgnoreCase("New Campaign")).isEmpty();
    }

    @Test
    void aFailureMidCommitRollsBackTheWholeBatch() throws IOException {
        AssessmentImportPlan plan = service.plan(csv("""
                name,appId,applicationName,assessmentType,startDate,durationDays,campaign
                First,APP-NEW,Brand New,Penetration Test,2026-10-05,5,New Campaign
                Second,APP-001,,Penetration Test,2026-10-05,5,
                """), CAN_CREATE_CAMPAIGNS);
        // Break the second row after planning, as if its type vanished between preview and commit.
        plan.rows().get(1).request().setAssessmentTypeId("no-such-type");

        assertThatThrownBy(() -> committer.commit(plan, "importer"))
                .isInstanceOf(com.faction.clientportal.exception.ResourceNotFoundException.class);

        assertThat(assessmentRepository.count()).isZero();
        assertThat(applicationRepository.findAllByAppIdIgnoreCase("APP-NEW")).isEmpty();
        assertThat(campaignRepository.findAllByNameIgnoreCase("New Campaign")).isEmpty();
    }

    @Test
    void withoutNotifyOnlyTheExtensionEventFires() throws IOException {
        importCsv("""
                name,appId,assessmentType,startDate,durationDays,assessors
                Quiet,APP-001,Penetration Test,2026-10-05,5,jane.doe
                """, false);

        org.mockito.Mockito.verifyNoInteractions(eventEmailSender);
        // Not verifyNoInteractions: persisting may legitimately notify for other reasons (e.g. the
        // chat post); what must not happen is the assessor/creation notifications.
        org.mockito.Mockito.verify(notificationService, org.mockito.Mockito.never()).send(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.matches("ASSESSOR_ASSIGNED|ASSESSMENT_CREATED"),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(extensionEventService).assessmentChanged(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(com.faction.extender.AssessmentManager.Operation.Create));
    }

    @Test
    void withNotifyAssessorsAndStakeholdersHearAboutIt() throws IOException {
        // Announcements go out after the batch commits, never from inside its transaction.
        List<Boolean> inTransaction = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            inTransaction.add(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            return null;
        }).when(notificationService).send(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        importCsv("""
                name,appId,assessmentType,startDate,durationDays,assessors
                Loud,APP-001,Penetration Test,2026-10-05,5,jane.doe
                """, true);

        org.mockito.Mockito.verify(notificationService).send(
                org.mockito.ArgumentMatchers.eq("jane.doe"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("ASSESSOR_ASSIGNED"),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(eventEmailSender).send(org.mockito.ArgumentMatchers.any());
        assertThat(inTransaction).isNotEmpty().containsOnly(false);
    }

    // ── Final review fixes ─────────────────────────────────────────────────

    @org.springframework.boot.test.mock.mockito.SpyBean private WorkflowCatalogService workflowCatalogService;

    @Test
    void aBatchLargerThanOneFlushChunkSharesItsNewApplicationAndCampaign() throws IOException {
        StringBuilder body = new StringBuilder("name,appId,applicationName,assessmentType,startDate,durationDays,campaign\n");
        for (int i = 1; i <= 250; i++) {
            body.append("Row ").append(i).append(",APP-BULK,Bulk App,Penetration Test,2026-10-05,5,Bulk Campaign\n");
        }
        org.mockito.Mockito.clearInvocations(workflowCatalogService);

        var result = importCsv(body.toString(), false);

        assertThat(result.getCreated()).isEqualTo(250);
        assertThat(result.getCreatedApplications()).containsExactly("Bulk App");
        assertThat(result.getCreatedCampaigns()).containsExactly("Bulk Campaign");
        List<Application> apps = applicationRepository.findAllByAppIdIgnoreCase("APP-BULK");
        assertThat(apps).hasSize(1);
        Campaign campaign = campaignRepository.findAllByNameIgnoreCase("Bulk Campaign").get(0);
        assertThat(assessmentRepository.findAll()).hasSize(250).allSatisfy(a -> {
            assertThat(a.getApplicationId()).isEqualTo(apps.get(0).getId());
            assertThat(a.getCampaignId()).isEqualTo(campaign.getId());
        });
        // One "Assessment scheduled" chat post per row, on the one application.
        assertThat(applicationRepository.findById(apps.get(0).getId()).orElseThrow().getComments()).hasSize(250);
        // The workflow catalog is loaded once for the batch, not once per row.
        org.mockito.Mockito.verify(workflowCatalogService, org.mockito.Mockito.times(1)).load();
    }

    @Test
    void externalUsersCannotBeAssessorsButCanBeManagers() throws IOException {
        userRepository.save(User.builder().username("client.contact").email("client@example.org")
                .firstName("Client").lastName("Contact").password("x").loginOption(LoginOption.NATIVE)
                .isInternal(false).createdAt(LocalDateTime.now()).build());

        var p = preview("""
                name,appId,assessmentType,startDate,durationDays,assessors,engagementManager,remediationManager
                One,APP-001,Penetration Test,2026-10-05,5,jane.doe;CLIENT.CONTACT,,
                Two,APP-001,Penetration Test,2026-10-05,5,jane.doe,client.contact,client@example.org
                """);

        assertThat(p.getRows().get(0).getErrors())
                .containsExactly("'CLIENT.CONTACT' isn't an internal user, so can't be an assessor");
        assertThat(p.getRows().get(0).getAssessors()).containsExactly("Jane Doe");
        assertThat(p.getRows().get(1).getErrors()).isEmpty();
    }

    @Test
    void creatingACampaignNeedsPermissionToCreateCampaigns() throws IOException {
        String body = """
                name,appId,assessmentType,startDate,durationDays,campaign
                One,APP-001,Penetration Test,2026-10-05,5,Brand New Campaign
                Two,APP-001,Penetration Test,2026-10-05,5,general
                """;

        var denied = service.preview(csv(body), Set.of("assessments:create:all"));
        assertThat(denied.isValid()).isFalse();
        assertThat(denied.getNewCampaignCount()).isZero();
        assertThat(denied.getRows().get(0).getErrors()).containsExactly(
                "Campaign 'Brand New Campaign' doesn't exist, and you don't have permission to create campaigns");
        assertThat(denied.getRows().get(0).isNewCampaign()).isFalse();
        // An existing campaign needs no extra permission.
        assertThat(denied.getRows().get(1).getErrors()).isEmpty();

        assertThatThrownBy(() -> service.importCsv(csv(body), false, "importer", Set.of("assessments:create:all")))
                .isInstanceOf(com.faction.clientportal.exception.AssessmentImportInvalidException.class);
        assertThat(campaignRepository.findAllByNameIgnoreCase("Brand New Campaign")).isEmpty();

        var allowed = service.preview(csv(body), Set.of("assessments:create:all", "campaigns:create:all"));
        assertThat(allowed.isValid()).isTrue();
        assertThat(allowed.getNewCampaignCount()).isEqualTo(1);

        var superAdmin = service.preview(csv(body), Set.of("super_admin"));
        assertThat(superAdmin.isValid()).isTrue();
        assertThat(superAdmin.getNewCampaignCount()).isEqualTo(1);
    }

    @Test
    void moreRowsThanTheCapAreRejected() {
        StringBuilder body = new StringBuilder("name,appId,assessmentType,startDate,durationDays\n");
        for (int i = 0; i <= 2000; i++) {
            body.append("Row ").append(i).append(",APP-001,Penetration Test,2026-10-05,5\n");
        }

        assertThatThrownBy(() -> preview(body.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Too many rows: 2001 (the limit is 2,000). Split the file and import each part");
    }

    @Test
    void anOversizedFileIsRejectedBeforeParsing() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) 'x');
        var file = new MockMultipartFile("file", "assessments.csv", "text/csv", big);

        assertThatThrownBy(() -> service.preview(file, CAN_CREATE_CAMPAIGNS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The file is too large (the limit is 10 MB)");
    }

    @Test
    void templateQuotesHeaderCellsThatNeedIt() throws IOException {
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Odd Report").assessmentTypeId(pentest.getId()).version(1).active(true)
                .userDefinedFields(new ArrayList<>(List.of(
                        UserDefinedField.builder().id("f-cost").variableName("cost, center")
                                .displayName("Cost center").fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.ASSESSMENT).build(),
                        UserDefinedField.builder().id("f-formula").variableName("=calc")
                                .displayName("Formula").fieldType(FieldType.STRING)
                                .fieldScope(FieldScope.ASSESSMENT).build())))
                .createdAt(LocalDateTime.now()).build());

        String header = service.template().lines().findFirst().orElseThrow();

        assertThat(header).endsWith(",\"'=calc\",\"cost, center\",environment,ticket");
        // And the quoted header reads back as the same columns.
        List<List<String>> parsed = com.faction.clientportal.util.CsvReader.read(
                new java.io.ByteArrayInputStream(service.template().getBytes(StandardCharsets.UTF_8)));
        assertThat(parsed.get(0)).contains("cost, center", "'=calc");
        assertThat(preview(service.template()).getRows()).hasSize(1);
    }

    @Test
    void aTypeWithSeveralTemplatesAndNoDefaultNeedsOneNamed() throws IOException {
        reportTemplateRepository.save(ReportTemplate.builder()
                .name("Second Pentest Report").assessmentTypeId(pentest.getId()).version(1).active(true)
                .userDefinedFields(new ArrayList<>()).createdAt(LocalDateTime.now()).build());

        var p = preview("""
                name,appId,assessmentType,startDate,durationDays,reportTemplate
                Unnamed,APP-001,Penetration Test,2026-10-05,5,
                Named,APP-001,Penetration Test,2026-10-05,5,pentest report
                """);

        assertThat(p.getRows().get(0).getErrors()).containsExactly(
                "Assessment type 'Penetration Test' has 2 report templates; name one in reportTemplate");
        assertThat(p.getRows().get(1).getErrors()).isEmpty();
    }

    @Test
    void aTypeWithNoTemplateYetOnlyRejectsCustomFields() throws IOException {
        assessmentTypeRepository.save(AssessmentType.builder().name("Red Team Op").createdAt(LocalDateTime.now()).build());

        var p = preview("""
                name,appId,assessmentType,startDate,durationDays,ticket
                Plain,APP-001,Red Team Op,2026-10-05,5,
                With Field,APP-001,Red Team Op,2026-10-05,5,SEC-1
                """);

        assertThat(p.getRows().get(0).getErrors()).isEmpty();
        assertThat(p.getRows().get(1).getErrors()).containsExactly(
                "Assessment type 'Red Team Op' has no report template yet, so custom fields can't be set");
    }

    @Test
    void duplicateColumnsAreRejected() {
        assertThatThrownBy(() -> preview("name,appId,assessmentType,startDate,durationDays,NAME\nx,APP-001,y,2026-01-01,1,z\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Column 'NAME' appears more than once");
        assertThatThrownBy(() -> preview("name,appId,assessmentType,startDate,durationDays,ticket,Ticket\nx,APP-001,y,2026-01-01,1,a,b\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Column 'Ticket' appears more than once");
    }

    @Test
    void durationDaysHasAnUpperBound() throws IOException {
        var p = preview("""
                name,appId,assessmentType,startDate,durationDays
                Ten Years,APP-001,Penetration Test,2026-10-05,3650
                Too Long,APP-001,Penetration Test,2026-10-05,3651
                """);

        assertThat(p.getRows().get(0).getErrors()).isEmpty();
        assertThat(p.getRows().get(1).getErrors()).containsExactly("durationDays must be at most 3650");
    }

    @Test
    void scopeLineBreaksOfEveryKindBecomeBreaks() throws IOException {
        AssessmentImportPlan plan = service.plan(csv(
                "name,appId,assessmentType,startDate,durationDays,scope\r\n"
                + "One,APP-001,Penetration Test,2026-10-05,5,\"a\r\nb\rc\nd\"\r\n"), CAN_CREATE_CAMPAIGNS);

        assertThat(plan.rows().get(0).request().getScope()).isEqualTo("<p>a<br>b<br>c<br>d</p>");
    }
}
