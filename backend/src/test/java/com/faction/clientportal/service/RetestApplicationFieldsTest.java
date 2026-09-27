package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.RetestDto;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Retest;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.RetestRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A retest carries its application's name and human-facing ID, so the Completed retests list can
 * show and search them without a lookup per row in the browser.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestApplicationFieldsTest extends TestContainersConfig {

    @Autowired private RetestService retestService;
    @Autowired private RetestRepository retestRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;

    @Test
    void aRetestCarriesItsApplicationsNameAndAppId() {
        String appId = "APP-" + UUID.randomUUID();
        Application app = applicationRepository.save(Application.builder()
                .appId(appId).name("Card Gateway " + appId).createdAt(LocalDateTime.now()).build());
        String asmt = assessmentRepository.save(Assessment.builder()
                .name("A").assessmentTypeId("t").status("Testing").applicationId(app.getId())
                .createdAt(LocalDateTime.now()).build()).getId();
        String vuln = vulnerabilityRepository.save(Vulnerability.builder()
                .name("SQLi").assessmentId(asmt).severity(VulnerabilitySeverity.HIGH).order(0)
                .status("Open").openedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
        Retest r = retestRepository.save(Retest.builder()
                .vulnerabilityId(vuln).assessmentId(asmt).applicationId(app.getId()).status("PASSED")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        RetestDto dto = retestService.getById(r.getId());

        assertThat(dto.getApplicationName()).isEqualTo("Card Gateway " + appId);
        assertThat(dto.getApplicationAppId()).isEqualTo(appId);
    }

    @Test
    void aRetestWithNoApplicationLeavesThemBlank() {
        Retest r = retestRepository.save(Retest.builder()
                .vulnerabilityId("missing").assessmentId("missing").status("PASSED")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        RetestDto dto = retestService.getById(r.getId());

        assertThat(dto.getApplicationName()).isNull();
        assertThat(dto.getApplicationAppId()).isNull();
    }
}
