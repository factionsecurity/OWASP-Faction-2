package com.faction.clientportal.service.ai;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.model.VulnerabilitySeverity;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AiToolExecutorTest extends TestContainersConfig {

    @Autowired private AiToolExecutor aiToolExecutor;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private VulnerabilityRepository vulnerabilityRepository;
    @Autowired private ObjectMapper objectMapper;

    private Assessment assessmentA;
    private Assessment assessmentB;
    private Vulnerability vulnInA;
    private Vulnerability vulnInB;

    @BeforeEach
    void setUp() {
        vulnerabilityRepository.deleteAll();
        assessmentRepository.deleteAll();

        assessmentA = assessmentRepository.save(Assessment.builder().name("Assessment A").build());
        assessmentB = assessmentRepository.save(Assessment.builder().name("Assessment B").build());

        vulnInA = vulnerabilityRepository.save(Vulnerability.builder()
                .assessmentId(assessmentA.getId())
                .name("SQL Injection in login")
                .severity(VulnerabilitySeverity.CRITICAL)
                .description("<p>The login form is <strong>injectable</strong>.</p>")
                .recommendation("<p>Use parameterized queries.</p>")
                .build());
        vulnInB = vulnerabilityRepository.save(Vulnerability.builder()
                .assessmentId(assessmentB.getId())
                .name("XSS in search")
                .severity(VulnerabilitySeverity.HIGH)
                .build());
    }

    @Test
    void listVulnerabilities_returnsOnlyCurrentAssessment() throws Exception {
        String result = aiToolExecutor.execute(
                new AiToolCall("1", "list_vulnerabilities", "{}"), assessmentA, false);

        JsonNode arr = objectMapper.readTree(result);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("name").asText()).isEqualTo("SQL Injection in login");
        assertThat(result).doesNotContain("XSS in search");
    }

    @Test
    void getVulnerability_deniesVulnerabilityFromOtherAssessment() {
        String result = aiToolExecutor.execute(
                new AiToolCall("1", "get_vulnerability",
                        "{\"vulnerability_id\": \"" + vulnInB.getId() + "\"}"), assessmentA, false);

        assertThat(result).contains("error");
        assertThat(result).doesNotContain("XSS in search");
    }

    @Test
    void getVulnerability_returnsStrippedDetailsForOwnAssessment() throws Exception {
        String result = aiToolExecutor.execute(
                new AiToolCall("1", "get_vulnerability",
                        "{\"vulnerability_id\": \"" + vulnInA.getId() + "\"}"), assessmentA, false);

        JsonNode node = objectMapper.readTree(result);
        assertThat(node.get("name").asText()).isEqualTo("SQL Injection in login");
        assertThat(node.get("description").asText()).isEqualTo("The login form is injectable .");
        assertThat(node.get("severity").asText()).isEqualTo("CRITICAL");
    }

    @Test
    void searchVulnerabilities_matchesOnlyWithinAssessment() throws Exception {
        String hit = aiToolExecutor.execute(
                new AiToolCall("1", "search_vulnerabilities", "{\"query\": \"injection\"}"), assessmentA, false);
        assertThat(objectMapper.readTree(hit)).hasSize(1);

        String miss = aiToolExecutor.execute(
                new AiToolCall("1", "search_vulnerabilities", "{\"query\": \"XSS\"}"), assessmentA, false);
        assertThat(objectMapper.readTree(miss)).isEmpty();
    }

    @Test
    void getAssessmentContext_includesSeverityCounts() throws Exception {
        String result = aiToolExecutor.execute(
                new AiToolCall("1", "get_assessment_context", "{}"), assessmentA, false);

        JsonNode node = objectMapper.readTree(result);
        assertThat(node.get("name").asText()).isEqualTo("Assessment A");
        assertThat(node.get("vulnerabilityCountsBySeverity").get("CRITICAL").asInt()).isEqualTo(1);
    }

    @Test
    void unknownTool_returnsError() {
        String result = aiToolExecutor.execute(
                new AiToolCall("1", "drop_all_tables", "{}"), assessmentA, false);
        assertThat(result).contains("Unknown tool");
    }

    @Test
    void webTools_areAbsentUnlessAllowed() {
        assertThat(aiToolExecutor.buildToolDefinitions(false))
                .extracting(AiToolDefinition::getName)
                .doesNotContain("web_search", "fetch_url");
        assertThat(aiToolExecutor.buildToolDefinitions(true))
                .extracting(AiToolDefinition::getName)
                .contains("web_search", "fetch_url");
    }

    @Test
    void webTools_rejectedWhenPromptDisallowsWeb() {
        String search = aiToolExecutor.execute(
                new AiToolCall("1", "web_search", "{\"query\": \"owasp\"}"), assessmentA, false);
        assertThat(search).contains("not permitted");

        String fetch = aiToolExecutor.execute(
                new AiToolCall("1", "fetch_url", "{\"url\": \"https://example.com\"}"), assessmentA, false);
        assertThat(fetch).contains("not permitted");
    }

    @Test
    void fetchUrl_blocksPrivateAndLoopbackAddresses() {
        // SSRF guard: even with web allowed, internal targets are refused
        for (String url : new String[]{
                "http://127.0.0.1/", "http://localhost/", "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.1/", "http://192.168.1.1/", "ftp://example.com/"}) {
            String result = aiToolExecutor.execute(
                    new AiToolCall("1", "fetch_url", "{\"url\": \"" + url + "\"}"), assessmentA, true);
            assertThat(result).as("should refuse %s", url).contains("error");
            assertThat(result).doesNotContain("\"content\"");
        }
    }

    @Test
    void webSearch_reportsWhenNotConfigured() {
        // No WebSearchConfig persisted → search unavailable, reported cleanly (not thrown)
        String result = aiToolExecutor.execute(
                new AiToolCall("1", "web_search", "{\"query\": \"owasp top 10\"}"), assessmentA, true);
        assertThat(result).contains("error");
    }
}
