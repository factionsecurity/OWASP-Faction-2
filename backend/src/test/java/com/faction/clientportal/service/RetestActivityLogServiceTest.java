package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RetestCompletionLogDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Organization;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.User;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.OrganizationRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retest activity log — what was verified in a window, by whom, and with what verdict.
 *
 * <p>The window is the thing worth pinning: "this week" has to mean the whole of the first and
 * last day, and it has to be the <em>completion</em> date that decides membership, not when the
 * retest was created or scheduled.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestActivityLogServiceTest extends TestContainersConfig {

    @Autowired private RetestActivityLogService service;
    @Autowired private RetestRepository retestRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    private String assessmentId;
    private String vulnId;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
        organizationRepository.deleteAll();
        userRepository.deleteAll();

        Organization org = organizationRepository.save(
                Organization.builder().name("Contoso").build());

        Application app = new Application();
        app.setName("Payments API");
        app.setOrganizationId(org.getId());
        app.setCreatedAt(LocalDateTime.now());
        app = applicationRepository.save(app);

        Assessment assessment = assessmentRepository.save(Assessment.builder()
                .name("Q3 Pentest").applicationId(app.getId()).organizationId(org.getId())
                .status("COMPLETED").createdAt(LocalDateTime.now()).build());
        assessmentId = assessment.getId();

        vulnId = vulnerabilityRepository.save(Vulnerability.builder()
                .name("SQL Injection").severity(VulnerabilitySeverity.HIGH)
                .assessmentId(assessmentId).status("Open")
                .openedAt(LocalDateTime.now().minusDays(30)).createdAt(LocalDateTime.now())
                .build()).getId();

        userRepository.save(User.builder()
                .username("rverifier").firstName("Robin").lastName("Verifier")
                .email("robin@example.com").password("x").loginOption(LoginOption.NATIVE)
                .isInternal(true).failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    /** A completed retest closed {@code daysAgo} days ago. */
    private String completed(String status, int daysAgo, String verifier) {
        return retestRepository.save(Retest.builder()
                .vulnerabilityId(vulnId).assessmentId(assessmentId)
                .status(status).result("PASSED".equals(status) ? "PASS" : "FAIL")
                .closedDate(LocalDateTime.now().minusDays(daysAgo))
                .completedBy(verifier)
                .comment("checked")
                .createdBy("rverifier").lastUpdatedBy("rverifier")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build()).getId();
    }

    private LocalDateTime startOf(int daysAgo) {
        return LocalDate.now().minusDays(daysAgo).atStartOfDay();
    }

    private LocalDateTime endOfToday() {
        return LocalDate.now().atTime(LocalTime.MAX);
    }

    private List<RetestCompletionLogDto> lastWeek(String result) {
        return service.list(startOf(6), endOfToday(), result, PAGE).getContent();
    }

    @Test
    void listsRetestsVerifiedInTheWindow() {
        completed("PASSED", 1, "rverifier");
        completed("FAILED", 3, "rverifier");

        assertThat(lastWeek(null))
                .extracting(RetestCompletionLogDto::getStatus)
                .containsExactlyInAnyOrder("PASSED", "FAILED");
    }

    @Test
    void completionsOutsideTheWindowAreExcluded() {
        completed("PASSED", 1, "rverifier");
        completed("PASSED", 30, "rverifier");

        assertThat(lastWeek(null)).hasSize(1);
    }

    @Test
    void openAndCancelledRetestsAreNotCompletions() {
        // Neither has a verdict, so neither belongs in a log of what was verified.
        retestRepository.save(Retest.builder()
                .vulnerabilityId(vulnId).assessmentId(assessmentId).status("IN_PROGRESS")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        retestRepository.save(Retest.builder()
                .vulnerabilityId(vulnId).assessmentId(assessmentId).status("CANCELLED")
                .closedDate(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        assertThat(lastWeek(null)).isEmpty();
        assertThat(service.summary(startOf(6), endOfToday()).getTotal()).isZero();
    }

    @Test
    void aRetestClosedTodayIsInsideTheWindow() {
        // The window ends at the end of today, not its start — otherwise "this week" silently
        // drops everything verified since midnight.
        completed("PASSED", 0, "rverifier");

        assertThat(lastWeek(null)).hasSize(1);
    }

    @Test
    void theResultFilterNarrowsToOneVerdict() {
        completed("PASSED", 1, "rverifier");
        completed("FAILED", 2, "rverifier");

        assertThat(lastWeek("PASS")).extracting(RetestCompletionLogDto::getStatus).containsExactly("PASSED");
        assertThat(lastWeek("FAIL")).extracting(RetestCompletionLogDto::getStatus).containsExactly("FAILED");
        assertThat(lastWeek("nonsense")).hasSize(2); // unrecognised → both, not none
    }

    @Test
    void totalsCountThePeriodNotThePage() {
        completed("PASSED", 1, "rverifier");
        completed("PASSED", 2, "rverifier");
        completed("FAILED", 3, "rverifier");

        var summary = service.summary(startOf(6), endOfToday());
        assertThat(summary.getPassed()).isEqualTo(2);
        assertThat(summary.getFailed()).isEqualTo(1);
        assertThat(summary.getTotal()).isEqualTo(3);

        // One row per page must not change the totals.
        assertThat(service.list(startOf(6), endOfToday(), null, PageRequest.of(0, 1)).getContent()).hasSize(1);
    }

    @Test
    void rowsCarryTheFindingApplicationAndVerifier() {
        completed("PASSED", 1, "rverifier");

        assertThat(lastWeek(null)).singleElement().satisfies(row -> {
            assertThat(row.getVulnerabilityName()).isEqualTo("SQL Injection");
            assertThat(row.getSeverity()).isEqualTo("HIGH");
            assertThat(row.getApplicationName()).isEqualTo("Payments API");
            assertThat(row.getOrganizationName()).isEqualTo("Contoso");
            assertThat(row.getAssessmentName()).isEqualTo("Q3 Pentest");
            assertThat(row.getCompletedBy()).isEqualTo("rverifier");
            assertThat(row.getCompletedByName()).isEqualTo("Robin Verifier");
            assertThat(row.getComment()).isEqualTo("checked");
        });
    }

    @Test
    void aVerifierWhoNoLongerHasAnAccountStillLists() {
        // The log is history: deleting the user must not delete the record of what they did.
        completed("PASSED", 1, "someone-gone");

        assertThat(lastWeek(null)).singleElement().satisfies(row -> {
            assertThat(row.getCompletedBy()).isEqualTo("someone-gone");
            assertThat(row.getCompletedByName()).isNull();
        });
    }
}
