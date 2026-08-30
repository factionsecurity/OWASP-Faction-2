package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.AiProviderConfigDto;
import com.faction.clientportal.edition.Feature;
import com.faction.clientportal.security.RequiresFeature;
import com.faction.clientportal.dto.SaveAiProviderConfigRequest;
import com.faction.clientportal.dto.TestAiProviderRequest;
import com.faction.clientportal.dto.TestAiProviderResponse;
import com.faction.clientportal.dto.UpdateWebSearchConfigRequest;
import com.faction.clientportal.dto.WebSearchConfigDto;
import com.faction.clientportal.dto.AiAnonymizationConfigDto;
import com.faction.clientportal.dto.UpdateAiAnonymizationConfigRequest;
import com.faction.clientportal.dto.AiTokenUsageDayDto;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.AiProviderConfigService;
import com.faction.clientportal.service.WebSearchConfigService;
import com.faction.clientportal.service.AiAnonymizationConfigService;
import com.faction.clientportal.service.ai.AiTokenUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ai-config")
@RequiredArgsConstructor
@Tag(name = "AI Configuration", description = "AI provider configuration management")
public class AiProviderConfigController {

    private final AiProviderConfigService aiProviderConfigService;
    private final WebSearchConfigService webSearchConfigService;
    private final AiAnonymizationConfigService aiAnonymizationConfigService;
    private final AiTokenUsageService aiTokenUsageService;

    @GetMapping
    @RequiresPermission({Permission.AI_CONFIG_READ, Permission.AI_CONFIG_WRITE})
    @Operation(summary = "List AI providers",
            description = "Every configured AI provider and which one is active. API keys are returned "
                    + "masked — the stored secret is never sent back.")
    public ResponseEntity<JsonApiResponse<List<AiProviderConfigDto>>> getProviders() {
        return ResponseEntity.ok(JsonApiResponse.success(aiProviderConfigService.getProviders()));
    }

    @GetMapping("/web-search")
    @RequiresPermission({Permission.AI_CONFIG_READ, Permission.AI_CONFIG_WRITE})
    @Operation(summary = "Get web search configuration",
            description = "Whether AI prompts may search the web, and the provider and key backing it "
                    + "(key masked).")
    public ResponseEntity<JsonApiResponse<WebSearchConfigDto>> getWebSearchConfig() {
        return ResponseEntity.ok(JsonApiResponse.success(webSearchConfigService.getConfig()));
    }

    @PutMapping("/web-search")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Update web search configuration",
            description = "Enables or disables web search for AI prompts and sets its provider credentials.")
    public ResponseEntity<JsonApiResponse<WebSearchConfigDto>> updateWebSearchConfig(
            @RequestBody UpdateWebSearchConfigRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(webSearchConfigService.updateConfig(request)));
    }

    @PostMapping
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Add an AI provider",
            description = "Registers a provider (model, endpoint, API key). Marking it active switches "
                    + "every AI feature over to it.")
    public ResponseEntity<JsonApiResponse<AiProviderConfigDto>> createProvider(
            @RequestBody SaveAiProviderConfigRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(aiProviderConfigService.createProvider(request)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Update an AI provider",
            description = "Changes the provider's settings. Leave the API key blank to keep the stored one.")
    public ResponseEntity<JsonApiResponse<AiProviderConfigDto>> updateProvider(
            @PathVariable String id, @RequestBody SaveAiProviderConfigRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(aiProviderConfigService.updateProvider(id, request)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Delete an AI provider",
            description = "Removes the provider and its stored key. Deleting the active provider leaves AI "
                    + "features unconfigured until another is activated.")
    public ResponseEntity<JsonApiResponse<Void>> deleteProvider(@PathVariable String id) {
        aiProviderConfigService.deleteProvider(id);
        return ResponseEntity.ok(JsonApiResponse.success("AI provider deleted", null));
    }

    @PostMapping("/test")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Test an AI provider",
            description = "Sends a probe request to the supplied provider settings and reports whether the "
                    + "credentials and model answer. Nothing is saved.")
    public ResponseEntity<JsonApiResponse<TestAiProviderResponse>> testProvider(
            @RequestBody TestAiProviderRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(aiProviderConfigService.testProvider(request)));
    }

    @GetMapping("/anonymization")
    @RequiresPermission({Permission.AI_CONFIG_READ, Permission.AI_CONFIG_WRITE})
    @Operation(summary = "Get AI anonymization settings",
            description = "Which identifying values (hosts, client names, and the like) are stripped from "
                    + "text before it leaves for the AI provider.")
    public ResponseEntity<JsonApiResponse<AiAnonymizationConfigDto>> getAnonymizationConfig() {
        return ResponseEntity.ok(JsonApiResponse.success(aiAnonymizationConfigService.getConfig()));
    }

    @PutMapping("/anonymization")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Update AI anonymization settings",
            description = "Changes what gets redacted before text is sent to the AI provider.")
    public ResponseEntity<JsonApiResponse<AiAnonymizationConfigDto>> updateAnonymizationConfig(
            @RequestBody UpdateAiAnonymizationConfigRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(aiAnonymizationConfigService.updateConfig(request)));
    }

    // ── Token usage ───────────────────────────────────────────────────────────

    @GetMapping("/usage")
    @RequiresFeature(Feature.AI_OBSERVABILITY)
    @RequiresPermission({Permission.AI_CONFIG_READ, Permission.AI_CONFIG_WRITE})
    @Operation(summary = "Daily AI token usage",
            description = "Tokens consumed per day, summed across every user, provider and model. "
                    + "`from` and `to` are ISO dates (inclusive) and default to the start of last "
                    + "month through today, which is what the usage chart draws. Days with no AI "
                    + "activity are omitted rather than returned as zeros. Usage is recorded "
                    + "independently of AI request logging, so these totals are complete even while "
                    + "logging is off.")
    public ResponseEntity<JsonApiResponse<List<AiTokenUsageDayDto>>> getTokenUsage(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today.minusMonths(1).withDayOfMonth(1));
        LocalDate end = parseDate(to, today);
        return ResponseEntity.ok(JsonApiResponse.success(aiTokenUsageService.dailyTotals(start, end)));
    }

    /** The given ISO date, or {@code fallback} when it is absent or unparseable. */
    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
