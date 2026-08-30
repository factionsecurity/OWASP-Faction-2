package com.faction.clientportal.service.ai;

import com.faction.clientportal.model.WebSearchConfig;
import com.faction.clientportal.service.WebSearchConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Web tools for AI prompts: search the web and fetch a URL. Only reachable when
 * a prompt has web access enabled (enforced by {@link AiToolExecutor}).
 *
 * <p>{@code fetch_url} is a server-side request driven by model-chosen URLs, so it
 * is SSRF-guarded: only http/https, redirects disabled, and any host resolving to
 * a private/loopback/link-local/metadata address is rejected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebToolService {

    private static final int TIMEOUT_MS = 12_000;
    private static final int MAX_FETCH_BYTES = 300_000;
    private static final int MAX_TEXT_CHARS = 8_000;
    private static final int MAX_SEARCH_RESULTS = 6;

    private final WebSearchConfigService webSearchConfigService;
    private final ObjectMapper objectMapper;

    public boolean isSearchConfigured() {
        WebSearchConfig config = webSearchConfigService.getOrCreate();
        return config.isEnabled()
                && config.getEncryptedApiKey() != null && !config.getEncryptedApiKey().isBlank();
    }

    /** Returns a JSON string of search results, or a JSON error object. */
    public String search(String query) {
        if (query == null || query.isBlank()) {
            return error("query is required");
        }
        WebSearchConfig config = webSearchConfigService.getOrCreate();
        if (!config.isEnabled()) {
            return error("Web search is not enabled. An administrator can enable it in AI Configuration.");
        }
        String apiKey;
        try {
            apiKey = webSearchConfigService.getDecryptedApiKey(config);
        } catch (Exception e) {
            log.warn("Failed to decrypt web search API key: {}", e.getMessage());
            return error("The web search API key could not be decrypted.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return error("Web search has no API key configured.");
        }
        try {
            return switch (config.getProvider()) {
                case BRAVE -> searchBrave(query, apiKey);
                case TAVILY -> searchTavily(query, apiKey);
                case SERPER -> searchSerper(query, apiKey);
            };
        } catch (Exception e) {
            log.warn("Web search failed ({}): {}", config.getProvider(), e.getMessage());
            return error("Web search failed: " + e.getMessage());
        }
    }

    private String searchBrave(String query, String apiKey) throws Exception {
        String url = "https://api.search.brave.com/res/v1/web/search?count=" + MAX_SEARCH_RESULTS
                + "&q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("X-Subscription-Token", apiKey);
        ResponseEntity<String> resp = restTemplate().exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        JsonNode results = objectMapper.readTree(resp.getBody()).path("web").path("results");
        return formatResults(results, "title", "url", "description");
    }

    private String searchTavily(String query, String apiKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("max_results", MAX_SEARCH_RESULTS);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate().postForEntity(
                "https://api.tavily.com/search", new HttpEntity<>(body.toString(), headers), String.class);
        JsonNode results = objectMapper.readTree(resp.getBody()).path("results");
        return formatResults(results, "title", "url", "content");
    }

    private String searchSerper(String query, String apiKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("q", query);
        body.put("num", MAX_SEARCH_RESULTS);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-KEY", apiKey);
        ResponseEntity<String> resp = restTemplate().postForEntity(
                "https://google.serper.dev/search", new HttpEntity<>(body.toString(), headers), String.class);
        JsonNode results = objectMapper.readTree(resp.getBody()).path("organic");
        return formatResults(results, "title", "link", "snippet");
    }

    private String formatResults(JsonNode results, String titleKey, String urlKey, String snippetKey) {
        ArrayNode out = objectMapper.createArrayNode();
        if (results.isArray()) {
            int count = 0;
            for (JsonNode r : results) {
                if (count++ >= MAX_SEARCH_RESULTS) break;
                ObjectNode item = out.addObject();
                item.put("title", r.path(titleKey).asText(""));
                item.put("url", r.path(urlKey).asText(""));
                item.put("snippet", truncate(r.path(snippetKey).asText(""), 400));
            }
        }
        return out.toString();
    }

    /** Fetches a URL's readable text, or a JSON error object. SSRF-guarded. */
    public String fetch(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return error("url is required");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            return error("Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return error("Only http and https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return error("URL has no host");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(addr)) {
                    return error("Refusing to fetch a private or internal address");
                }
            }
        } catch (UnknownHostException e) {
            return error("Could not resolve host: " + host);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FactionAI/1.0");
            headers.set("Accept", "text/html,application/xhtml+xml,application/json,text/plain");
            ResponseEntity<byte[]> resp = restTemplate().exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] bytes = resp.getBody();
            if (bytes == null || bytes.length == 0) {
                return error("Empty response");
            }
            int len = Math.min(bytes.length, MAX_FETCH_BYTES);
            String raw = new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);

            MediaType contentType = resp.getHeaders().getContentType();
            String text = (contentType != null && contentType.getSubtype().contains("html"))
                    ? htmlToText(raw) : raw.replaceAll("\\s+", " ").trim();

            ObjectNode node = objectMapper.createObjectNode();
            node.put("url", uri.toString());
            node.put("content", truncate(text, MAX_TEXT_CHARS));
            if (bytes.length > MAX_FETCH_BYTES || text.length() > MAX_TEXT_CHARS) {
                node.put("truncated", true);
            }
            return node.toString();
        } catch (Exception e) {
            log.warn("URL fetch failed for {}: {}", host, e.getMessage());
            return error("Failed to fetch URL: " + e.getMessage());
        }
    }

    private boolean isBlockedAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()   // includes 169.254.0.0/16 metadata range
                || addr.isSiteLocalAddress()   // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()
                || isUniqueLocalIpv6(addr);
    }

    /** IPv6 unique-local (fc00::/7), not covered by isSiteLocalAddress. */
    private boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /** Strips scripts/styles/tags to plain text. */
    static String htmlToText(String html) {
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private RestTemplate restTemplate() {
        // Redirects disabled: a 3xx could point from an allowed host to an internal one.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("error", message));
        } catch (Exception e) {
            return "{\"error\": \"unknown\"}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
