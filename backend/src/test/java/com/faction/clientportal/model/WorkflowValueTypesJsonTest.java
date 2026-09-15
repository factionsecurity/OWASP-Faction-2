package com.faction.clientportal.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workflow value types are stored as JSON in jsonb columns and returned by the workflow API, so
 * moving them to top-level classes must not change a single property name.
 */
class WorkflowValueTypesJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anSlaKeepsItsJsonShape() throws Exception {
        String json = mapper.writeValueAsString(new VulnerabilitySla("HIGH", 60, 30));

        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(
                "{\"severity\":\"HIGH\",\"pastDueDays\":60,\"warningDays\":30}"));
        assertThat(mapper.readValue(json, VulnerabilitySla.class))
                .isEqualTo(new VulnerabilitySla("HIGH", 60, 30));
    }

    @Test
    void aStageKeepsItsJsonShape() throws Exception {
        String json = mapper.writeValueAsString(new RemediationStage("staging", "Staging"));

        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(
                "{\"id\":\"staging\",\"name\":\"Staging\"}"));
        assertThat(mapper.readValue(json, RemediationStage.class))
                .isEqualTo(new RemediationStage("staging", "Staging"));
    }
}
