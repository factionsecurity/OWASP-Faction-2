package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.ApplicationDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The applications-list "Open Issues" column: {@code searchApplications} enriches each
 * ApplicationDto with a batched open-, non-informational-finding count. Replaces the old
 * per-assessment fan-out on the Applications tab.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationOpenIssueCountTest extends TestContainersConfig {

    @Autowired private ApplicationService applicationService;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        applicationRepository.deleteAll();
    }

    @Test
    void searchApplications_populatesOpenIssueCountPerApplication() {
        String appA = application("App A");
        String appB = application("App B");
        String aA = assessment(appA);
        String aB = assessment(appB);

        LocalDateTime now = LocalDateTime.now();
        // App A: 2 open non-informational, plus a closed one and an informational (both excluded) → 2.
        vuln(aA, VulnerabilitySeverity.HIGH, now.minusDays(1), null, "None");
        vuln(aA, VulnerabilitySeverity.CRITICAL, now.minusDays(1), null, "Open");
        vuln(aA, VulnerabilitySeverity.MEDIUM, now.minusDays(1), now.minusDays(2), "Closed"); // closed
        vuln(aA, VulnerabilitySeverity.INFORMATIONAL, now.minusDays(1), null, "None");        // advisory
        // App B: only a not-yet-opened finding → 0.
        vuln(aB, VulnerabilitySeverity.HIGH, null, null, "None");

        List<ApplicationDto> apps = applicationService
                .searchApplications(null, PageRequest.of(0, 10), superAdmin())
                .getContent();

        assertThat(dto(apps, appA).getOpenIssueCount()).isEqualTo(2L);
        assertThat(dto(apps, appB).getOpenIssueCount()).isEqualTo(0L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ApplicationDto dto(List<ApplicationDto> apps, String id) {
        return apps.stream().filter(a -> a.getId().equals(id)).findFirst().orElseThrow();
    }

    private String application(String name) {
        return applicationRepository.save(Application.builder()
                .name(name + "-" + System.nanoTime())
                .organizationId("org-1")
                .build()).getId();
    }

    private String assessment(String applicationId) {
        return assessmentRepository.save(Assessment.builder()
                .name("A-" + System.nanoTime())
                .applicationId(applicationId)
                .assessmentTypeId("type-1")
                .organizationId("org-1")
                .status("IN_PROGRESS")
                .createdAt(LocalDateTime.now())
                .build()).getId();
    }

    private void vuln(String assessmentId, VulnerabilitySeverity sev,
                      LocalDateTime openedAt, LocalDateTime closedAt, String status) {
        vulnerabilityRepository.save(Vulnerability.builder()
                .name("v-" + System.nanoTime())
                .severity(sev)
                .assessmentId(assessmentId)
                .order(0)
                .status(status)
                .openedAt(openedAt)
                .closedAt(closedAt)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private Authentication superAdmin() {
        return new UsernamePasswordAuthenticationToken("super", null,
                List.of(new SimpleGrantedAuthority(RequiresPermissionAuthorizationManager.SUPER_ADMIN)));
    }
}
