package com.faction.clientportal.service;

import com.faction.clientportal.model.AssessmentWorkflow;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.testsupport.TestWorkflows;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Mapping a finding onto another workflow keeps built-in statuses and the target's own statuses,
 * returns anything else to Open, and recalculates its SLA dates under the target from the original
 * opened date.
 */
class WorkflowMappingServiceTest {

    private static final LocalDateTime OPENED = LocalDateTime.of(2099, 1, 1, 9, 0);

    private final SlaService slaService = new SlaService(
            mock(AssessmentWorkflowConfigService.class), mock(WorkflowCatalogService.class), mock(AssessmentRepository.class));
    private final WorkflowMappingService mapping = new WorkflowMappingService(slaService);
    private final AssessmentWorkflow defaults = AssessmentWorkflow.defaultWorkflowBuilder().build();
    private final AssessmentWorkflow second = TestWorkflows.secondWorkflow();

    private static Vulnerability high(String status) {
        return Vulnerability.builder().severity(VulnerabilitySeverity.HIGH).status(status).openedAt(OPENED).build();
    }

    @Test
    void aCustomStatusTheTargetLacksBecomesOpen() {
        Vulnerability finding = high("Risk Accepted");

        mapping.mapFinding(finding, defaults);

        assertThat(finding.getStatus()).isEqualTo("Open");
    }

    @Test
    void aCustomStatusTheTargetHasIsKept() {
        Vulnerability finding = high("Risk Accepted");

        mapping.mapFinding(finding, second);

        assertThat(finding.getStatus()).isEqualTo("Risk Accepted");
    }

    @Test
    void builtInStatusesAreAlwaysKept() {
        for (String builtIn : AssessmentWorkflows.BUILT_IN_VULNERABILITY_STATUSES) {
            Vulnerability finding = high(builtIn);

            mapping.mapFinding(finding, second);

            assertThat(finding.getStatus()).as(builtIn).isEqualTo(builtIn);
        }
    }

    @Test
    void aTargetWithNoVulnerabilityStatusesListMapsACustomStatusToOpen() {
        AssessmentWorkflow target = AssessmentWorkflow.builder().id("x").name("X").vulnerabilityStatuses(null).build();
        Vulnerability finding = high("Risk Accepted");

        mapping.mapFinding(finding, target);

        assertThat(finding.getStatus()).isEqualTo("Open");
    }

    @Test
    void aFindingWithoutAStatusKeepsNone() {
        Vulnerability finding = high(null);

        mapping.mapFinding(finding, second);

        assertThat(finding.getStatus()).isNull();
    }

    @Test
    void datesAreRecalculatedUnderTheTargetFromTheOriginalOpenedDate() {
        Vulnerability finding = high("Open");
        finding.setDueAt(OPENED.plusDays(60));
        finding.setWarningAt(OPENED.plusDays(30));

        mapping.mapFinding(finding, second);

        assertThat(finding.getOpenedAt()).isEqualTo(OPENED);
        assertThat(finding.getDueAt()).isEqualTo(OPENED.plusDays(14));
        assertThat(finding.getWarningAt()).isEqualTo(OPENED.plusDays(9));
    }
}
