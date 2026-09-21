package com.faction.clientportal.dto;

import com.faction.clientportal.model.Assessment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssessmentDtoWorkflowIdTest {

    @Test
    void fromEntityCarriesTheAssessmentsWorkflowId() {
        Assessment entity = Assessment.builder().id("a-1").workflowId("second-workflow").build();

        assertThat(AssessmentDto.fromEntity(entity).getWorkflowId()).isEqualTo("second-workflow");
    }
}
