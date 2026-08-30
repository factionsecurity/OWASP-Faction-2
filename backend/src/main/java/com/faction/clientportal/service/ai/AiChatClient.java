package com.faction.clientportal.service.ai;

import com.faction.clientportal.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocking chat-completion client speaking both the OpenAI wire format
 * (OpenAI, OpenRouter, Azure OpenAI, OpenAI-compatible endpoints) and the
 * Anthropic Messages format, with tool-calling support in both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_AZURE_API_VERSION = "2024-10-21";
    private static final int MAX_TOKENS = 4096;
    private static final int TIMEOUT_MS = 60_000;

    private final ObjectMapper objectMapper;

    /**
     * Sends one chat turn. Throws IllegalStateException with a user-presentable
     * message on transport/HTTP errors.
     */
    public AiChatResponse chat(AiProviderConfig provider, String apiKey, String model,
                               String systemPrompt, List<AiChatMessage> messages,
                               List<AiToolDefinition> tools) {
        return switch (provider.getProviderType()) {
            case ANTHROPIC -> callAnthropic(provider, apiKey, model, systemPrompt, messages, tools);
            default -> callOpenAiFormat(provider, apiKey, model, systemPrompt, messages, tools);
        };
    }

    // ── OpenAI wire format (OpenAI / OpenRouter / Azure / compatible) ──

    private AiChatResponse callOpenAiFormat(AiProviderConfig provider, String apiKey, String model,
                                            String systemPrompt, List<AiChatMessage> messages,
                                            List<AiToolDefinition> tools) {
        String baseUrl = trimTrailingSlash(resolveBaseUrl(provider));
        String url;
        HttpHeaders headers = jsonHeaders();
        switch (provider.getProviderType()) {
            case AZURE_OPENAI -> {
                String version = provider.getApiVersion() != null && !provider.getApiVersion().isBlank()
                        ? provider.getApiVersion() : DEFAULT_AZURE_API_VERSION;
                url = baseUrl + "/openai/deployments/" + model + "/chat/completions?api-version=" + version;
                if (apiKey != null) headers.set("api-key", apiKey);
            }
            default -> {
                url = baseUrl + "/chat/completions";
                if (apiKey != null) headers.setBearerAuth(apiKey);
            }
        }

        ObjectNode body = objectMapper.createObjectNode();
        if (provider.getProviderType() != com.faction.clientportal.model.AiProviderType.AZURE_OPENAI) {
            body.put("model", model);
        }
        ArrayNode msgs = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            msgs.addObject().put("role", "system").put("content", systemPrompt);
        }
        for (AiChatMessage m : messages) {
            switch (m.getRole()) {
                case USER -> msgs.addObject().put("role", "user").put("content", m.getContent());
                case ASSISTANT -> {
                    ObjectNode a = msgs.addObject().put("role", "assistant");
                    if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                        ArrayNode calls = a.putArray("tool_calls");
                        for (AiToolCall c : m.getToolCalls()) {
                            ObjectNode call = calls.addObject();
                            call.put("id", c.getId());
                            call.put("type", "function");
                            call.putObject("function")
                                    .put("name", c.getName())
                                    .put("arguments", c.getArgumentsJson() != null ? c.getArgumentsJson() : "{}");
                        }
                    } else {
                        a.put("content", m.getContent());
                    }
                }
                case TOOL_RESULT -> msgs.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.getToolCallId())
                        .put("content", m.getToolResult());
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (AiToolDefinition t : tools) {
                ObjectNode tool = toolsNode.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.getName());
                fn.put("description", t.getDescription());
                fn.set("parameters", buildJsonSchema(t));
            }
        }

        JsonNode root = post(url, headers, body);
        AiTokenUsage usage = openAiUsage(root.path("usage"));
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
            List<AiToolCall> calls = new ArrayList<>();
            for (JsonNode call : toolCallsNode) {
                calls.add(new AiToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText("{}")));
            }
            return new AiChatResponse(null, calls, usage);
        }
        return new AiChatResponse(message.path("content").asText(""), null, usage);
    }

    // ── Anthropic Messages format ──

    private AiChatResponse callAnthropic(AiProviderConfig provider, String apiKey, String model,
                                         String systemPrompt, List<AiChatMessage> messages,
                                         List<AiToolDefinition> tools) {
        String url = trimTrailingSlash(resolveBaseUrl(provider)) + "/messages";
        HttpHeaders headers = jsonHeaders();
        if (apiKey != null) headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        ArrayNode msgs = body.putArray("messages");
        ArrayNode pendingToolResults = null; // consecutive tool results merge into one user turn
        for (AiChatMessage m : messages) {
            if (m.getRole() == AiChatMessage.Role.TOOL_RESULT) {
                if (pendingToolResults == null) {
                    pendingToolResults = msgs.addObject().put("role", "user").putArray("content");
                }
                pendingToolResults.addObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.getToolCallId())
                        .put("content", m.getToolResult());
                continue;
            }
            pendingToolResults = null;
            if (m.getRole() == AiChatMessage.Role.USER) {
                msgs.addObject().put("role", "user").put("content", m.getContent());
            } else { // ASSISTANT
                ObjectNode a = msgs.addObject().put("role", "assistant");
                if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                    ArrayNode content = a.putArray("content");
                    for (AiToolCall c : m.getToolCalls()) {
                        ObjectNode block = content.addObject();
                        block.put("type", "tool_use");
                        block.put("id", c.getId());
                        block.put("name", c.getName());
                        block.set("input", parseJsonOrEmpty(c.getArgumentsJson()));
                    }
                } else {
                    a.put("content", m.getContent());
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = body.putArray("tools");
            for (AiToolDefinition t : tools) {
                ObjectNode tool = toolsNode.addObject();
                tool.put("name", t.getName());
                tool.put("description", t.getDescription());
                tool.set("input_schema", buildJsonSchema(t));
            }
        }

        JsonNode root = post(url, headers, body);
        AiTokenUsage usage = anthropicUsage(root.path("usage"));
        List<AiToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText();
            if ("tool_use".equals(type)) {
                calls.add(new AiToolCall(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        block.path("input").toString()));
            } else if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            }
        }
        if (!calls.isEmpty()) {
            return new AiChatResponse(null, calls, usage);
        }
        return new AiChatResponse(text.toString(), null, usage);
    }

    // ── Token usage ──

    /** OpenAI-format usage block. Absent fields read as 0, so a provider that omits usage costs nothing. */
    static AiTokenUsage openAiUsage(JsonNode usage) {
        return new AiTokenUsage(usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    /**
     * Anthropic usage block. Cached input is billed separately from {@code input_tokens} and is
     * reported in its own fields, so it is added in rather than double-counted or dropped.
     */
    static AiTokenUsage anthropicUsage(JsonNode usage) {
        int input = usage.path("input_tokens").asInt(0)
                + usage.path("cache_creation_input_tokens").asInt(0)
                + usage.path("cache_read_input_tokens").asInt(0);
        return new AiTokenUsage(input, usage.path("output_tokens").asInt(0));
    }

    // ── Shared plumbing ──

    private ObjectNode buildJsonSchema(AiToolDefinition tool) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.valueToTree(tool.getProperties()));
        ArrayNode required = schema.putArray("required");
        if (tool.getRequired() != null) {
            tool.getRequired().forEach(required::add);
        }
        return schema;
    }

    private JsonNode parseJsonOrEmpty(String json) {
        try {
            return json == null || json.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode post(String url, HttpHeaders headers, ObjectNode body) {
        try {
            ResponseEntity<String> response = buildRestTemplate()
                    .postForEntity(url, new HttpEntity<>(body.toString(), headers), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            log.warn("AI provider call failed: HTTP {} — {}", e.getStatusCode().value(),
                    truncate(e.getResponseBodyAsString()));
            throw new IllegalStateException("AI provider returned HTTP " + e.getStatusCode().value()
                    + (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
                            ? " — check the API key in AI Configuration." : "."));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI provider call failed: {}", e.getMessage());
            throw new IllegalStateException("Could not reach the AI provider: " + e.getMessage());
        }
    }

    private String resolveBaseUrl(AiProviderConfig provider) {
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            return provider.getBaseUrl();
        }
        String fallback = provider.getProviderType().getDefaultBaseUrl();
        if (fallback == null) {
            throw new IllegalStateException("AI provider \"" + provider.getName() + "\" has no base URL configured.");
        }
        return fallback;
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
