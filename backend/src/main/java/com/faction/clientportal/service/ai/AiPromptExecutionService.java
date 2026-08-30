package com.faction.clientportal.service.ai;

import com.faction.clientportal.dto.AiGenerationResponse;
import com.faction.clientportal.dto.AskAiRequest;
import com.faction.clientportal.dto.ExecuteAiPromptRequest;
import com.faction.clientportal.dto.SuggestAiTitleRequest;
import com.faction.clientportal.model.AiPromptTemplate;
import com.faction.clientportal.model.AiProviderConfig;
import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.repository.AiPromptTemplateRepository;
import com.faction.clientportal.repository.AiProviderConfigRepository;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.service.AiProviderConfigService;
import com.faction.clientportal.service.AssessmentService;
import com.faction.clientportal.service.WebSearchConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs admin-defined prompt templates and freeform "Ask AI" requests against a
 * single assessment, using a bounded tool-use loop for data retrieval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPromptExecutionService {

    private static final int MAX_TOOL_ITERATIONS = 6;

    private final AiPromptTemplateRepository aiPromptTemplateRepository;
    private final AiProviderConfigRepository aiProviderConfigRepository;
    private final AiProviderConfigService aiProviderConfigService;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentService assessmentService;
    private final WebSearchConfigService webSearchConfigService;
    private final AiChatClient aiChatClient;
    private final AiToolExecutor aiToolExecutor;
    private final AnonymizationService anonymizationService;
    private final AiCallObserver aiCallObserver;
    private final AiTokenUsageService aiTokenUsageService;

    public AiGenerationResponse executePrompt(ExecuteAiPromptRequest request, Authentication authentication) {
        if (request.getPromptId() == null || request.getPromptId().isBlank()) {
            return AiGenerationResponse.failure("promptId is required.");
        }
        AiPromptTemplate template = aiPromptTemplateRepository.findById(request.getPromptId()).orElse(null);
        if (template == null || !template.isEnabled()) {
            return AiGenerationResponse.failure("Prompt not found or disabled.");
        }
        Assessment assessment = authorizeAndLoad(request.getAssessmentId(), authentication);

        String userMessage = template.getPrompt()
                + editingContext(assessment, request.getVulnerabilityId(), request.getCurrentText());
        AiCallObserver.CallContext logCtx = new AiCallObserver.CallContext(
                username(authentication), "EXECUTE_PROMPT", assessment.getId(),
                request.getVulnerabilityId(), template.getId(), template.getName());
        return run(template.getProviderId(), template.getModel(), assessment, userMessage,
                template.isAllowWebAccess(), logCtx);
    }

    public AiGenerationResponse ask(AskAiRequest request, Authentication authentication) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return AiGenerationResponse.failure("A question or instruction is required.");
        }
        Assessment assessment = authorizeAndLoad(request.getAssessmentId(), authentication);

        String userMessage = "This request is part of an authorized security assessment; the user is "
                + "writing penetration test report content.\n\nInstruction: " + request.getQuestion().trim()
                + editingContext(assessment, request.getVulnerabilityId(), request.getCurrentText());
        // Freeform Ask AI gets web access only when an admin has opted it in globally.
        boolean allowWeb = webSearchConfigService.getOrCreate().isAllowInAskAi();
        AiCallObserver.CallContext logCtx = new AiCallObserver.CallContext(
                username(authentication), "ASK", assessment.getId(),
                request.getVulnerabilityId(), null, null);
        return run(null, null, assessment, userMessage, allowWeb, logCtx);
    }

    private static String username(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    /**
     * Derives a concise vulnerability title from the description/details text.
     * Single model call, no tools; returns plain text (not HTML).
     */
    public AiGenerationResponse suggestTitle(SuggestAiTitleRequest request, Authentication authentication) {
        String description = AiToolExecutor.stripHtml(request.getDescription());
        String details = AiToolExecutor.stripHtml(request.getDetails());
        if (description.isBlank() && details.isBlank()) {
            return AiGenerationResponse.failure("Add a description or details first, then derive a title.");
        }
        authorizeAndLoad(request.getAssessmentId(), authentication);

        AiProviderConfig provider;
        String model;
        try {
            provider = resolveProvider(null);
            model = resolveModel(provider, null);
        } catch (IllegalStateException e) {
            return AiGenerationResponse.failure(e.getMessage());
        }

        String apiKey;
        try {
            apiKey = aiProviderConfigService.getDecryptedApiKey(provider);
        } catch (Exception e) {
            log.warn("Failed to decrypt API key for AI provider {}: {}", provider.getId(), e.getMessage());
            return AiGenerationResponse.failure(
                    "The stored API key for provider \"" + provider.getName() + "\" could not be decrypted.");
        }

        String systemPrompt = "You are a penetration tester naming vulnerability findings for a report. "
                + "Given a finding's description and technical details, respond with ONLY a concise, specific "
                + "title in plain text — no quotes, no HTML, no markdown, no trailing period. Aim for 4 to 12 "
                + "words, name the weakness class and the affected component when identifiable, "
                + "e.g. \"SQL Injection in Login Form\" or \"Missing Rate Limiting on Password Reset Endpoint\".";
        StringBuilder userMessage = new StringBuilder();
        if (!description.isBlank()) {
            userMessage.append("Description:\n").append(description);
        }
        if (!details.isBlank()) {
            if (userMessage.length() > 0) userMessage.append("\n\n");
            userMessage.append("Technical details:\n").append(details);
        }

        long start = System.currentTimeMillis();
        AiCallObserver.CallContext logCtx = new AiCallObserver.CallContext(
                username(authentication), "SUGGEST_TITLE", request.getAssessmentId(),
                request.getVulnerabilityId(), null, null);
        AnonymizationService.Session anon = anonymizationService.newSession();
        List<AiChatMessage> messages;
        String rawResponse = null;
        boolean ok = false;
        String error = null;
        AiTokenUsage usage = AiTokenUsage.NONE;
        AiGenerationResponse result;
        try {
            String masked = anonymizationService.mask(userMessage.toString(), anon);
            messages = List.of(AiChatMessage.user(masked));
            AiChatResponse response = aiChatClient.chat(provider, apiKey, model, systemPrompt, messages, null);
            usage = usage.plus(response.getUsage());
            rawResponse = cleanTitle(response.getContent());
            String title = anonymizationService.restore(rawResponse, anon);
            if (title.isBlank()) {
                error = "The AI returned an empty response.";
                result = AiGenerationResponse.failure(error);
            } else {
                ok = true;
                result = AiGenerationResponse.ok(title);
            }
        } catch (AnonymizationService.AnonymizationUnavailableException | IllegalStateException e) {
            error = e.getMessage();
            messages = List.of();
            result = AiGenerationResponse.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("AI title suggestion failed", e);
            error = "AI generation failed: " + e.getMessage();
            messages = List.of();
            result = AiGenerationResponse.failure(error);
        }
        aiCallObserver.record(logCtx, provider.getName(), model, anon.isEnabled(),
                systemPrompt, messages, rawResponse, ok, error, System.currentTimeMillis() - start, usage);
        aiTokenUsageService.record(logCtx.username(), provider.getName(), model, usage);
        return result;
    }

    /** Normalizes model output to a single plain-text title line. */
    static String cleanTitle(String content) {
        if (content == null) return "";
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : "";
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        // Take the first non-blank line BEFORE stripping HTML — stripHtml collapses
        // newlines, which would fold explanatory lines into the title.
        String firstLine = "";
        for (String line : s.split("\n")) {
            if (!line.isBlank()) {
                firstLine = line.trim();
                break;
            }
        }
        s = AiToolExecutor.stripHtml(firstLine);
        s = s.replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.length() > 200) s = s.substring(0, 200).trim();
        return s;
    }

    /**
     * Applies the standard assessment read gate (throws ResourceNotFoundException on
     * missing or inaccessible assessments), then returns the entity for tool scoping.
     */
    private Assessment authorizeAndLoad(String assessmentId, Authentication authentication) {
        if (assessmentId == null || assessmentId.isBlank()) {
            throw new IllegalArgumentException("assessmentId is required");
        }
        assessmentService.getAssessment(assessmentId, authentication);
        return assessmentRepository.findByIdAndDeletedAtIsNull(assessmentId).orElseThrow();
    }

    private String editingContext(Assessment assessment, String vulnerabilityId, String currentText) {
        StringBuilder sb = new StringBuilder();
        if (vulnerabilityId != null && !vulnerabilityId.isBlank()) {
            // Only mention ids that actually belong to this assessment
            boolean inAssessment = aiToolExecutor
                    .execute(new AiToolCall("ctx", "get_vulnerability",
                            "{\"vulnerability_id\": \"" + vulnerabilityId.replace("\"", "") + "\"}"),
                            assessment, false)
                    .indexOf("\"error\"") < 0;
            if (inAssessment) {
                sb.append("\n\nThe user is currently editing the vulnerability with id \"")
                        .append(vulnerabilityId)
                        .append("\". Use the get_vulnerability tool if its details are relevant.");
            }
        }
        if (currentText != null && !currentText.isBlank()) {
            sb.append("\n\nCurrent content of the text field being edited (HTML):\n")
                    .append(currentText);
        }
        return sb.toString();
    }

    private AiGenerationResponse run(String providerId, String model, Assessment assessment,
                                    String userMessage, boolean allowWeb,
                                    AiCallObserver.CallContext logCtx) {
        long start = System.currentTimeMillis();
        AnonymizationService.Session anon = anonymizationService.newSession();
        List<AiChatMessage> messages = new ArrayList<>();
        String systemPrompt = systemPrompt(assessment, allowWeb);
        String providerName = null;
        String usedModel = null;
        String rawResponse = null;
        boolean ok = false;
        String error = null;
        // One logical request can span several provider calls, each billed separately —
        // totalling them here is what keeps a tool-heavy prompt from under-reporting.
        AiTokenUsage usage = AiTokenUsage.NONE;
        AiGenerationResponse result;

        try {
            AiProviderConfig provider = resolveProvider(providerId);
            String resolvedModel = resolveModel(provider, model);
            providerName = provider.getName();
            usedModel = resolvedModel;
            String apiKey;
            try {
                apiKey = aiProviderConfigService.getDecryptedApiKey(provider);
            } catch (Exception e) {
                log.warn("Failed to decrypt API key for AI provider {}: {}", provider.getId(), e.getMessage());
                throw new IllegalStateException(
                        "The stored API key for provider \"" + provider.getName() + "\" could not be decrypted.");
            }

            List<AiToolDefinition> tools = aiToolExecutor.buildToolDefinitions(allowWeb);
            // Mask before anything leaves for the provider; a shared session keeps the same
            // value mapped to the same placeholder across the prompt and every tool result.
            messages.add(AiChatMessage.user(anonymizationService.mask(userMessage, anon)));

            result = AiGenerationResponse.failure(
                    "The AI did not produce a final answer within the allowed number of steps.");
            for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
                AiChatResponse response = aiChatClient.chat(
                        provider, apiKey, resolvedModel, systemPrompt, messages, tools);
                usage = usage.plus(response.getUsage());
                if (response.hasToolCalls()) {
                    messages.add(AiChatMessage.assistantToolCalls(response.getToolCalls()));
                    for (AiToolCall call : response.getToolCalls()) {
                        String toolResult = aiToolExecutor.execute(call, assessment, allowWeb);
                        messages.add(AiChatMessage.toolResult(call.getId(), call.getName(),
                                anonymizationService.mask(toolResult, anon)));
                    }
                    continue;
                }
                rawResponse = cleanOutput(response.getContent());
                String content = anonymizationService.restore(rawResponse, anon);
                if (content.isBlank()) {
                    error = "The AI returned an empty response.";
                    result = AiGenerationResponse.failure(error);
                } else {
                    ok = true;
                    result = AiGenerationResponse.ok(content);
                }
                break;
            }
            if (!ok && error == null) {
                error = result.getMessage();
            }
        } catch (AnonymizationService.AnonymizationUnavailableException | IllegalStateException e) {
            error = e.getMessage();
            result = AiGenerationResponse.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("AI generation failed", e);
            error = "AI generation failed: " + e.getMessage();
            result = AiGenerationResponse.failure(error);
        }

        aiCallObserver.record(logCtx, providerName, usedModel, anon.isEnabled(),
                systemPrompt, messages, rawResponse, ok, error, System.currentTimeMillis() - start, usage);
        aiTokenUsageService.record(logCtx.username(), providerName, usedModel, usage);
        return result;
    }

    private String systemPrompt(Assessment assessment, boolean allowWeb) {
        String webClause = allowWeb
                ? " You may also use web_search and fetch_url to find authoritative public sources "
                    + "(OWASP, CWE, CVE, vendor advisories) and add reference links; only cite URLs you "
                    + "actually found via those tools, and render links as <a href=\"URL\">text</a>."
                : "";
        return "You are a cybersecurity expert assistant helping write penetration testing report content. "
                + "You have access to tools that query data from the current assessment only: \""
                + assessment.getName() + "\". Use the tools to gather the information you need before writing "
                + "your answer. Never invent vulnerabilities or data — only use what the tools return."
                + webClause + "\n\n"
                + "Output rules: respond with ONLY an HTML fragment suitable for a rich text editor. "
                + "Allowed tags: <p>, <br>, <strong>, <em>, <u>, <ul>, <ol>, <li>, <h2>, <h3>, <table>, "
                + "<thead>, <tbody>, <tr>, <th>, <td>, <blockquote>, <code>, <a href>. "
                + "Do not use markdown syntax or code fences. Do not include <html>, <head>, or <body> tags. "
                + "Do not add any preamble, commentary, or explanation — output only the content itself.";
    }

    private AiProviderConfig resolveProvider(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            AiProviderConfig provider = aiProviderConfigRepository.findById(providerId).orElse(null);
            if (provider == null) {
                throw new IllegalStateException("The AI provider configured for this prompt no longer exists.");
            }
            if (!provider.isEnabled()) {
                throw new IllegalStateException(
                        "The AI provider \"" + provider.getName() + "\" is disabled in AI Configuration.");
            }
            return provider;
        }
        List<AiProviderConfig> enabled = aiProviderConfigRepository.findAll().stream()
                .filter(AiProviderConfig::isEnabled)
                .sorted(Comparator.comparing(AiProviderConfig::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (enabled.isEmpty()) {
            throw new IllegalStateException("No enabled AI provider is configured. "
                    + "Add one under Administration > AI Configuration.");
        }
        // Prefer a provider with a default model chosen
        Optional<AiProviderConfig> withDefault = enabled.stream()
                .filter(p -> p.getDefaultModel() != null && !p.getDefaultModel().isBlank())
                .findFirst();
        return withDefault.orElse(enabled.get(0));
    }

    private String resolveModel(AiProviderConfig provider, String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()) {
            return provider.getDefaultModel();
        }
        if (provider.getModels() != null && !provider.getModels().isEmpty()) {
            return provider.getModels().get(0);
        }
        throw new IllegalStateException("No model is configured for AI provider \"" + provider.getName()
                + "\". Set a default model in AI Configuration.");
    }

    /** Strips code fences and document wrappers the model may add despite instructions. */
    static String cleanOutput(String content) {
        if (content == null) return "";
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : "";
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        s = s.replaceAll("(?is)<!DOCTYPE[^>]*>", "")
                .replaceAll("(?is)</?html[^>]*>", "")
                .replaceAll("(?is)<head>.*?</head>", "")
                .replaceAll("(?is)</?body[^>]*>", "")
                .trim();
        if (!s.isBlank() && s.indexOf('<') < 0) {
            // Plain text fallback: wrap paragraphs
            StringBuilder sb = new StringBuilder();
            for (String para : s.split("\\n\\s*\\n")) {
                sb.append("<p>").append(para.trim().replace("\n", "<br>")).append("</p>");
            }
            s = sb.toString();
        }
        return s;
    }
}
