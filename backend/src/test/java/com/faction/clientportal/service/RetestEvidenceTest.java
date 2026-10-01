package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CompleteRetestRequest;
import com.faction.clientportal.dto.RetestDto;
import com.faction.clientportal.dto.UpdateRetestRequest;
import com.faction.clientportal.exception.BusinessRuleException;
import com.faction.clientportal.model.*;
import com.faction.clientportal.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retest evidence: editable while the retest is open and after Pass/Fail, locked once a retest
 * report includes it unless the assessment's workflow allows edits after a report.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetestEvidenceTest extends TestContainersConfig {

    @Autowired private RetestService retestService;
    @Autowired private RetestRepository retestRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private VulnerabilityStageCompletionRepository stageCompletionRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentWorkflowRepository workflowRepository;

    private String assessmentId;
    private String vulnId;

    @BeforeEach
    void setUp() {
        retestRepository.deleteAll();
        stageCompletionRepository.deleteAll();
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();
        assessmentId = assessmentRepository.save(Assessment.builder()
                .name("A").assessmentTypeId("t").status("Complete")
                .createdAt(LocalDateTime.now()).build()).getId();
        vulnId = vulnerabilityRepository.save(Vulnerability.builder()
                .name("SQLi").assessmentId(assessmentId).severity(VulnerabilitySeverity.HIGH).order(0)
                .status("Open").openedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
    }

    private Retest retest(String status) {
        return retestRepository.save(Retest.builder()
                .vulnerabilityId(vulnId).assessmentId(assessmentId).status(status)
                .assignedAssessorIds(List.of("u1"))
                .scheduledStartDate(LocalDateTime.now()).scheduledEndDate(LocalDateTime.now().plusDays(1))
                .createdBy("system").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private UpdateRetestRequest evidence(String html) {
        UpdateRetestRequest r = new UpdateRetestRequest();
        r.setEvidence(html);
        return r;
    }

    private void setWorkflowAllowsEdit(boolean allow) {
        AssessmentWorkflow wf = workflowRepository.findById(AssessmentWorkflow.DEFAULT_ID).orElseThrow();
        wf.setAllowRetestEvidenceEditAfterReport(allow);
        workflowRepository.save(wf);
    }

    @Test
    void evidenceSavesOnAnOpenRetestAndStampsUpdatedAt() {
        Retest r = retest("IN_PROGRESS");

        RetestDto dto = retestService.update(r.getId(), evidence("<p>still vulnerable</p>"), "tester");

        assertThat(dto.getEvidence()).isEqualTo("<p>still vulnerable</p>");
        assertThat(dto.getEvidenceUpdatedAt()).isNotNull();
        assertThat(dto.isEvidenceEditable()).isTrue();
    }

    @Test
    void evidenceStaysEditableAfterPassOrFail() {
        Retest r = retest("PASSED");

        RetestDto dto = retestService.update(r.getId(), evidence("<p>fixed</p>"), "tester");

        assertThat(dto.getEvidence()).isEqualTo("<p>fixed</p>");
    }

    @Test
    void completeAcceptsEvidence() {
        Retest r = retest("IN_PROGRESS");
        CompleteRetestRequest req = new CompleteRetestRequest();
        req.setResult("FAIL");
        req.setEvidence("<p>payload still fires</p>");

        RetestDto dto = retestService.complete(r.getId(), req, "tester");

        assertThat(dto.getEvidence()).isEqualTo("<p>payload still fires</p>");
    }

    @Test
    void lockedEvidenceIsRejectedWhenTheWorkflowDisallowsEdits() {
        setWorkflowAllowsEdit(false);
        Retest r = retest("PASSED");
        r.setEvidence("<p>original</p>");
        r.setEvidenceLockedAt(LocalDateTime.now());
        retestRepository.save(r);

        assertThatThrownBy(() -> retestService.update(r.getId(), evidence("<p>changed</p>"), "tester"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(RetestService.RETEST_EVIDENCE_LOCKED);
        assertThat(retestRepository.findById(r.getId()).orElseThrow().getEvidence()).isEqualTo("<p>original</p>");
        assertThat(retestService.getById(r.getId()).isEvidenceEditable()).isFalse();
    }

    @Test
    void lockedEvidenceIsEditableWhenTheWorkflowAllowsIt() {
        setWorkflowAllowsEdit(true);
        try {
            Retest r = retest("PASSED");
            r.setEvidenceLockedAt(LocalDateTime.now().minusMinutes(5));
            retestRepository.save(r);

            RetestDto dto = retestService.update(r.getId(), evidence("<p>corrected</p>"), "tester");

            assertThat(dto.getEvidence()).isEqualTo("<p>corrected</p>");
            assertThat(dto.getEvidenceUpdatedAt()).isAfter(dto.getEvidenceLockedAt());
            assertThat(dto.isEvidenceEditable()).isTrue();
        } finally {
            setWorkflowAllowsEdit(false);
        }
    }

    @Test
    void cancelledRetestEvidenceIsReadOnly() {
        Retest r = retest("CANCELLED");

        assertThatThrownBy(() -> retestService.update(r.getId(), evidence("<p>x</p>"), "tester"))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(retestService.getById(r.getId()).isEvidenceEditable()).isFalse();
    }

    @Test
    void anUpdateWithoutEvidenceLeavesLockedEvidenceAlone() {
        setWorkflowAllowsEdit(false);
        Retest r = retest("PASSED");
        r.setEvidence("<p>original</p>");
        r.setEvidenceLockedAt(LocalDateTime.now());
        retestRepository.save(r);
        UpdateRetestRequest commentOnly = new UpdateRetestRequest();
        commentOnly.setComment("<p>note</p>");

        RetestDto dto = retestService.update(r.getId(), commentOnly, "tester");

        assertThat(dto.getEvidence()).isEqualTo("<p>original</p>");
    }

    @Test
    void getAllFiltersByVulnerabilityForHistory() {
        retest("PASSED");
        retest("FAILED");
        String otherVuln = vulnerabilityRepository.save(Vulnerability.builder()
                .name("XSS").assessmentId(assessmentId).severity(VulnerabilitySeverity.LOW).order(1)
                .status("Open").openedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build()).getId();
        retestRepository.save(Retest.builder().vulnerabilityId(otherVuln).assessmentId(assessmentId)
                .status("PASSED").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        List<RetestDto> history = retestService.getAll(false, null, vulnId, "tester", null);

        assertThat(history).hasSize(2).allMatch(d -> d.getVulnerabilityId().equals(vulnId));
    }
}
