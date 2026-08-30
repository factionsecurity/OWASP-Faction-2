package com.faction.clientportal.controller.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, unauthenticated status endpoint reporting the deployed version
 * (stamped by the release workflow via the APP_VERSION build arg) and uptime.
 */
@RestController
@RequestMapping("/api/v1/status")
@Tag(name = "Status", description = "Public service status")
public class StatusController {

    private final String version;
    private final Instant startedAt = Instant.now();

    public StatusController(@Value("${app.version:dev}") String version) {
        this.version = version;
    }

    @GetMapping
    @Operation(summary = "Get service status",
               description = "Public endpoint reporting the deployed version and uptime. No authentication required.")
    public ResponseEntity<Map<String, Object>> status() {
        Duration uptime = Duration.between(startedAt, Instant.now());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", version);
        body.put("startedAt", startedAt.toString());
        body.put("uptimeSeconds", uptime.getSeconds());
        body.put("uptime", humanize(uptime));
        return ResponseEntity.ok(body);
    }

    private static String humanize(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(hours).append("h ");
        if (days > 0 || hours > 0 || minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString();
    }
}
