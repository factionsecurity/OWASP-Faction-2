package com.faction.clientportal.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Plain unit test (no Spring context): the builder's default collections must be mutable, since
 * callers elsewhere in the codebase add to / remove from them after building.
 */
class AssessmentWorkflowDefaultsTest {

    @Test
    void defaultStatusesListIsMutable() {
        AssessmentWorkflow workflow = AssessmentWorkflow.defaultWorkflowBuilder().build();

        assertThatCode(() -> workflow.getStatuses().add("Extra")).doesNotThrowAnyException();
        assertThat(workflow.getStatuses()).contains("Extra");
    }
}
