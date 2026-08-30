package com.faction.clientportal.service.ai;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.model.Vulnerability;
import com.faction.clientportal.repository.ApplicationRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.VulnerabilityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The internal "MCP-like" tool surface exposed to the model. Every tool is
 * hard-scoped to the assessment passed by the server: the model never supplies
 * an assessment id, and vulnerability lookups are filtered by that assessment,
 * so data from other assessments is unreachable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolExecutor {

    private final VulnerabilityRepository vulnerabilityRepository;
    private final ApplicationRepository applicationRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final WebToolService webToolService;
    private final ObjectMapper objectMapper;

    /**
     * Builds the tool set for a run. Web tools are included only when the prompt
     * allows web access; they are the sole tools not scoped to the assessment.
     */
    public List<AiToolDefinition> buildToolDefinitions(boolean allowWeb) {
        List<AiToolDefinition> tools = new java.util.ArrayList<>(assessmentTools());
        if (allowWeb) {
            tools.add(new AiToolDefinition(
                    "web_search",
                    "Search the public web for up-to-date information or authoritative sources "
                            + "(e.g. OWASP, CWE, CVE, vendor advisories) to cite as reference links. "
                            + "Returns a list of results with title, url, and snippet.",
                    Map.of("query", Map.of(
                            "type", "string",
                            "description", "The search query")),
                    List.of("query")));
            tools.add(new AiToolDefinition(
                    "fetch_url",
                    "Fetch the readable text content of a public web page by its URL, e.g. to confirm "
                            + "a reference before citing it. Only http/https public URLs are allowed.",
                    Map.of("url", Map.of(
                            "type", "string",
                            "description", "The absolute http(s) URL to fetch")),
                    List.of("url")));
        }
        return tools;
    }

    private List<AiToolDefinition> assessmentTools() {
        return List.of(
                new AiToolDefinition(
                        "get_assessment_context",
                        "Get details of the current assessment: name, type, status, scope, "
                                + "custom field values, and vulnerability counts by severity.",
                        Map.of(),
                        List.of()),
                new AiToolDefinition(
                        "list_vulnerabilities",
                        "List all vulnerabilities in the current assessment with id, name, severity, "
                                + "status, likelihood, and impact. Set include_details=true to also get "
                                + "each vulnerability's description, recommendation, and details text.",
                        Map.of("include_details", Map.of(
                                "type", "boolean",
                                "description", "Include full description/recommendation/details text")),
                        List.of()),
                new AiToolDefinition(
                        "get_vulnerability",
                        "Get the full details of one vulnerability in the current assessment by its id.",
                        Map.of("vulnerability_id", Map.of(
                                "type", "string",
                                "description", "The vulnerability id (from list_vulnerabilities)")),
                        List.of("vulnerability_id")),
                new AiToolDefinition(
                        "search_vulnerabilities",
                        "Search the current assessment's vulnerabilities by text across name, "
                                + "description, recommendation, and details.",
                        Map.of("query", Map.of(
                                "type", "string",
                                "description", "Case-insensitive text to search for")),
                        List.of("query"))
        );
    }

    /** Executes a tool call. Never throws — errors are returned as JSON for the model. */
    public String execute(AiToolCall call, Assessment assessment, boolean allowWeb) {
        try {
            JsonNode args = parseArgs(call.getArgumentsJson());
            return switch (call.getName()) {
                case "get_assessment_context" -> getAssessmentContext(assessment);
                case "list_vulnerabilities" -> listVulnerabilities(assessment,
                        args.path("include_details").asBoolean(false));
                case "get_vulnerability" -> getVulnerability(assessment,
                        args.path("vulnerability_id").asText(null));
                case "search_vulnerabilities" -> searchVulnerabilities(assessment,
                        args.path("query").asText(""));
                case "web_search" -> allowWeb
                        ? webToolService.search(args.path("query").asText(""))
                        : error("Web access is not permitted for this prompt.");
                case "fetch_url" -> allowWeb
                        ? webToolService.fetch(args.path("url").asText(""))
                        : error("Web access is not permitted for this prompt.");
                default -> error("Unknown tool: " + call.getName());
            };
        } catch (Exception e) {
            log.warn("AI tool {} failed: {}", call.getName(), e.getMessage());
            return error("Tool execution failed: " + e.getMessage());
        }
    }

    private String getAssessmentContext(Assessment assessment) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", assessment.getName());
        node.put("status", assessment.getStatus());
        applicationRepository.findById(assessment.getApplicationId() == null ? "" : assessment.getApplicationId())
                .ifPresent(app -> node.put("application", app.getName()));
        if (assessment.getAssessmentTypeId() != null) {
            assessmentTypeRepository.findById(assessment.getAssessmentTypeId())
                    .ifPresent(t -> node.put("assessmentType", t.getName()));
        }
        if (assessment.getScope() != null && !assessment.getScope().isBlank()) {
            node.put("scope", stripHtml(assessment.getScope()));
        }

        // Custom (user-defined) field values, keyed by display name
        Map<String, String> fields = new LinkedHashMap<>();
        List<UserDefinedField> defs = assessment.getFieldDefinitions();
        Map<String, String> values = assessment.getFieldValues();
        if (defs != null && values != null) {
            for (UserDefinedField def : defs) {
                String value = values.get(def.getId());
                if (value != null && !value.isBlank()) {
                    fields.put(def.getDisplayName(), stripHtml(value));
                }
            }
        }
        node.set("customFields", objectMapper.valueToTree(fields));

        Map<String, Integer> counts = new TreeMap<>();
        for (Vulnerability v : findVulns(assessment)) {
            String sev = v.getSeverity() != null ? v.getSeverity().name() : "UNRATED";
            counts.merge(sev, 1, Integer::sum);
        }
        node.set("vulnerabilityCountsBySeverity", objectMapper.valueToTree(counts));
        return objectMapper.writeValueAsString(node);
    }

    private String listVulnerabilities(Assessment assessment, boolean includeDetails) throws Exception {
        ArrayNode arr = objectMapper.createArrayNode();
        for (Vulnerability v : findVulns(assessment)) {
            arr.add(vulnSummary(v, includeDetails));
        }
        return objectMapper.writeValueAsString(arr);
    }

    private String getVulnerability(Assessment assessment, String vulnerabilityId) throws Exception {
        if (vulnerabilityId == null || vulnerabilityId.isBlank()) {
            return error("vulnerability_id is required");
        }
        // Filtered lookup: a vulnerability outside this assessment is simply not found.
        return vulnerabilityRepository
                .findByIdAndAssessmentIdAndDeletedAtIsNull(vulnerabilityId, assessment.getId())
                .map(v -> {
                    try {
                        ObjectNode node = vulnSummary(v, true);
                        node.put("assetLocation", nullSafe(v.getAssetLocation()));
                        node.put("cvssString", nullSafe(v.getCvssString()));
                        if (v.getCvssScore() != null) node.put("cvssScore", v.getCvssScore());
                        Map<String, String> fields = new LinkedHashMap<>();
                        if (v.getFieldDefinitions() != null && v.getFieldValues() != null) {
                            for (UserDefinedField def : v.getFieldDefinitions()) {
                                String value = v.getFieldValues().get(def.getId());
                                if (value != null && !value.isBlank()) {
                                    fields.put(def.getDisplayName(), stripHtml(value));
                                }
                            }
                        }
                        node.set("customFields", objectMapper.valueToTree(fields));
                        return objectMapper.writeValueAsString(node);
                    } catch (Exception e) {
                        return error("Failed to serialize vulnerability");
                    }
                })
                .orElse(error("No vulnerability with id " + vulnerabilityId + " in this assessment"));
    }

    private String searchVulnerabilities(Assessment assessment, String query) throws Exception {
        String q = query.toLowerCase();
        ArrayNode arr = objectMapper.createArrayNode();
        if (q.isBlank()) {
            return error("query is required");
        }
        for (Vulnerability v : findVulns(assessment)) {
            String haystack = String.join(" ",
                    nullSafe(v.getName()),
                    stripHtml(nullSafe(v.getDescription())),
                    stripHtml(nullSafe(v.getRecommendation())),
                    stripHtml(nullSafe(v.getDetails()))).toLowerCase();
            if (haystack.contains(q)) {
                arr.add(vulnSummary(v, false));
            }
        }
        return objectMapper.writeValueAsString(arr);
    }

    private List<Vulnerability> findVulns(Assessment assessment) {
        return vulnerabilityRepository.findByAssessmentIdAndDeletedAtIsNull(assessment.getId());
    }

    private ObjectNode vulnSummary(Vulnerability v, boolean includeDetails) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", v.getId());
        node.put("name", v.getName());
        node.put("severity", v.getSeverity() != null ? v.getSeverity().name() : "UNRATED");
        node.put("status", nullSafe(v.getStatus()));
        node.put("likelihood", nullSafe(v.getLikelihood()));
        node.put("impact", nullSafe(v.getImpact()));
        node.put("section", nullSafe(v.getSection()));
        if (includeDetails) {
            node.put("description", stripHtml(nullSafe(v.getDescription())));
            node.put("recommendation", stripHtml(nullSafe(v.getRecommendation())));
            node.put("details", stripHtml(nullSafe(v.getDetails())));
        }
        return node;
    }

    private JsonNode parseArgs(String json) {
        try {
            return json == null || json.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String error(String message) {
        return "{\"error\": " + quote(message) + "}";
    }

    private String quote(String s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"error\"";
        }
    }

    static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
