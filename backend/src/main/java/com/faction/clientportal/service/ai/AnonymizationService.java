package com.faction.clientportal.service.ai;

import com.faction.clientportal.model.AiAnonymizationConfig;
import com.faction.clientportal.service.AiAnonymizationConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reversible anonymization for AI content. When enabled, {@link #mask} replaces
 * secrets/PII with stable placeholders (e.g. {@code [[SECRET_1]]}) before text is
 * sent to the LLM, and {@link #restore} swaps the real values back into the model's
 * output. Placeholders are deliberately not angle-bracket/tag-like so they survive
 * HTML round-trips and the frontend sanitizer.
 *
 * <p>Detection combines built-in high-signal secret patterns (always) with Presidio
 * PII detection (when a URL is configured). If Presidio is configured but
 * unreachable, masking fails closed — {@link AnonymizationUnavailableException} is
 * thrown so the caller aborts rather than sending unmasked data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymizationService {

    private static final int TIMEOUT_MS = 8_000;

    private final AiAnonymizationConfigService configService;
    private final ObjectMapper objectMapper;

    /** Thrown when masking is required but the configured detector can't be reached. */
    public static class AnonymizationUnavailableException extends RuntimeException {
        public AnonymizationUnavailableException(String message) {
            super(message);
        }
    }

    /** A rule matching sensitive text. {@code group} is the capture group to mask (0 = whole match). */
    private record SecretRule(Pattern pattern, int group, String type) {}

    private static final List<SecretRule> SECRET_RULES = List.of(
            new SecretRule(Pattern.compile("-----BEGIN[\\s\\S]*?PRIVATE KEY-----[\\s\\S]*?-----END[\\s\\S]*?PRIVATE KEY-----"), 0, "SECRET"),
            new SecretRule(Pattern.compile("AKIA[0-9A-Z]{16}"), 0, "SECRET"),
            new SecretRule(Pattern.compile("AIza[0-9A-Za-z_\\-]{35}"), 0, "SECRET"),
            new SecretRule(Pattern.compile("sk-[A-Za-z0-9]{20,}"), 0, "SECRET"),
            new SecretRule(Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"), 0, "SECRET"),
            new SecretRule(Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"), 0, "SECRET"),
            new SecretRule(Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), 0, "SECRET"),
            // key: value / key = "value" assignments for common credential names
            new SecretRule(Pattern.compile(
                    "(?i)(?:password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?key|auth[_-]?token|token|bearer)"
                            + "[\"']?\\s*[:=]\\s*[\"']?([^\\s\"'<>]{6,})"), 1, "SECRET"),
            // long standalone base64/hex blobs (won't match hyphenated UUIDs)
            new SecretRule(Pattern.compile("\\b[A-Za-z0-9+/]{40,}={0,2}\\b"), 0, "SECRET"),
            new SecretRule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), 0, "EMAIL")
    );

    /** Per-request state: the enabled config plus the value↔placeholder maps for consistency. */
    public final class Session {
        private final boolean enabled;
        private final String presidioUrl;
        private final double scoreThreshold;
        private final Map<String, String> valueToPlaceholder = new LinkedHashMap<>();
        private final Map<String, String> placeholderToValue = new LinkedHashMap<>();
        private final Map<String, Integer> typeCounters = new LinkedHashMap<>();

        private Session(AiAnonymizationConfig config) {
            this.enabled = config.isEnabled();
            this.presidioUrl = config.getPresidioUrl();
            this.scoreThreshold = config.getScoreThreshold();
        }

        public boolean isEnabled() {
            return enabled;
        }

        private String placeholderFor(String value, String type) {
            return valueToPlaceholder.computeIfAbsent(value, v -> {
                int n = typeCounters.merge(type, 1, Integer::sum);
                String placeholder = "[[" + type + "_" + n + "]]";
                placeholderToValue.put(placeholder, v);
                return placeholder;
            });
        }
    }

    /** Starts a masking session using the current config snapshot. */
    public Session newSession() {
        return new Session(configService.getOrCreate());
    }

    private record Span(int start, int end, String type) {}

    /**
     * Masks sensitive spans in {@code text}, reusing placeholders for values already
     * seen in this session. Returns the text unchanged when anonymization is disabled.
     */
    public String mask(String text, Session session) {
        if (text == null || text.isBlank() || !session.enabled) {
            return text;
        }
        List<Span> spans = new ArrayList<>(findSecretSpans(text));
        if (session.presidioUrl != null && !session.presidioUrl.isBlank()) {
            spans.addAll(findPresidioSpans(text, session)); // throws if unreachable (fail-closed)
        }
        if (spans.isEmpty()) {
            return text;
        }
        List<Span> selected = resolveOverlaps(spans);

        // Replace right-to-left so earlier offsets stay valid.
        selected.sort(Comparator.comparingInt(Span::start).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (Span span : selected) {
            String value = text.substring(span.start(), span.end());
            sb.replace(span.start(), span.end(), session.placeholderFor(value, span.type()));
        }
        return sb.toString();
    }

    /** Restores real values into model output by reversing the session's placeholders. */
    public String restore(String text, Session session) {
        if (text == null || text.isEmpty() || !session.enabled) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> e : session.placeholderToValue.entrySet()) {
            if (result.contains(e.getKey())) {
                result = result.replace(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    private List<Span> findSecretSpans(String text) {
        List<Span> spans = new ArrayList<>();
        for (SecretRule rule : SECRET_RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                int start = m.start(rule.group());
                int end = m.end(rule.group());
                if (start >= 0 && end > start) {
                    spans.add(new Span(start, end, rule.type()));
                }
            }
        }
        return spans;
    }

    private List<Span> findPresidioSpans(String text, Session session) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("text", text);
            body.put("language", "en");
            body.put("score_threshold", session.scoreThreshold);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate().postForEntity(
                    session.presidioUrl + "/analyze", new HttpEntity<>(body.toString(), headers), String.class);

            List<Span> spans = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(resp.getBody())) {
                int start = node.path("start").asInt(-1);
                int end = node.path("end").asInt(-1);
                String type = node.path("entity_type").asText("PII");
                if (start >= 0 && end > start) {
                    spans.add(new Span(start, end, type));
                }
            }
            return spans;
        } catch (Exception e) {
            log.warn("Presidio analyze call failed: {}", e.getMessage());
            throw new AnonymizationUnavailableException(
                    "Anonymization is enabled but the Presidio service could not be reached.");
        }
    }

    /** Keeps non-overlapping spans, preferring earlier start then longer length. */
    private List<Span> resolveOverlaps(List<Span> spans) {
        spans.sort(Comparator.comparingInt(Span::start)
                .thenComparing(Comparator.comparingInt((Span s) -> s.end() - s.start()).reversed()));
        List<Span> kept = new ArrayList<>();
        int lastEnd = -1;
        for (Span s : spans) {
            if (s.start() >= lastEnd) {
                kept.add(s);
                lastEnd = s.end();
            }
        }
        return kept;
    }

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }
}
