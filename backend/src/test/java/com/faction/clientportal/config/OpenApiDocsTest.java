package com.faction.clientportal.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OpenApiDocsTest extends TestContainersConfig {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.version:dev}")
    private String appVersion;

    @Value("${app.backend-url:}")
    private String backendUrl;

    private JsonNode fetchApiDocs() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void apiDocs_IsPubliclyAccessible_AndDeclaresBearerAuth() throws Exception {
        JsonNode docs = fetchApiDocs();

        assertThat(docs.path("info").path("title").asText()).isEqualTo("Faction API");
        assertThat(docs.path("paths").size()).isGreaterThan(0);

        JsonNode scheme = docs.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    void apiDocs_ReportTheDeployedVersion_AndThisDeploymentsServerUrl() throws Exception {
        JsonNode docs = fetchApiDocs();

        // The release workflow stamps the tag in as APP_VERSION; a hardcoded version here
        // would go stale the first time a release shipped.
        assertThat(docs.path("info").path("version").asText()).isEqualTo(appVersion);

        // Swagger UI's "Try it out" posts at the first server, so it has to be this
        // deployment rather than the reader's own localhost.
        assertThat(docs.path("servers").get(0).path("url").asText())
                .isEqualTo(backendUrl.isBlank() ? "http://localhost:8080" : backendUrl);
    }

    @Test
    void everyOperation_HasSummaryAndTag() throws Exception {
        JsonNode paths = fetchApiDocs().path("paths");

        List<String> missingSummary = new ArrayList<>();
        List<String> missingTags = new ArrayList<>();

        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> pathEntry = it.next();
            for (Iterator<Map.Entry<String, JsonNode>> ops = pathEntry.getValue().fields(); ops.hasNext(); ) {
                Map.Entry<String, JsonNode> opEntry = ops.next();
                if (!HTTP_METHODS.contains(opEntry.getKey())) {
                    continue;
                }
                String endpoint = opEntry.getKey().toUpperCase() + " " + pathEntry.getKey();
                JsonNode operation = opEntry.getValue();
                if (operation.path("summary").asText().isBlank()) {
                    missingSummary.add(endpoint);
                }
                if (!operation.path("tags").isArray() || operation.path("tags").isEmpty()) {
                    missingTags.add(endpoint);
                }
            }
        }

        assertThat(missingSummary)
                .as("Endpoints missing an @Operation summary")
                .isEmpty();
        assertThat(missingTags)
                .as("Endpoints missing a @Tag")
                .isEmpty();
    }

    @Test
    void swaggerUi_IsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
