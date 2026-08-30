package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.AiGenerationResponse;
import com.faction.clientportal.dto.AiPromptSummaryDto;
import com.faction.clientportal.dto.AskAiRequest;
import com.faction.clientportal.dto.ExecuteAiPromptRequest;
import com.faction.clientportal.dto.SuggestAiTitleRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.AiPromptScope;
import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.service.AiPromptTemplateService;
import com.faction.clientportal.service.ai.AiPromptExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI actions available from rich text editors. Open to any authenticated user;
 * assessment access is enforced per request by the execution service (the same
 * read gate as the assessment endpoints), and all AI data tools are scoped to
 * that single assessment.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI Actions", description = "AI text generation for rich text editors")
public class AiActionsController {

    private final AiPromptTemplateService aiPromptTemplateService;
    private final AiPromptExecutionService aiPromptExecutionService;

    @GetMapping("/prompts")
    @AuthenticatedOnly
    @Operation(summary = "List available AI prompts",
            description = "The enabled admin-defined prompts for the given editor scope, in the order the "
                    + "editor's AI menu should show them. Disabled prompts are never returned.")
    public ResponseEntity<JsonApiResponse<List<AiPromptSummaryDto>>> getPrompts(
            @RequestParam AiPromptScope scope) {
        return ResponseEntity.ok(JsonApiResponse.success(
                aiPromptTemplateService.getEnabledPromptSummaries(scope)));
    }

    @PostMapping("/execute-prompt")
    @AuthenticatedOnly
    @Operation(summary = "Run a saved AI prompt",
            description = "Runs one of the admin-defined prompts against the supplied text and assessment "
                    + "context. Assessment access is re-checked per request, so a caller only ever gets "
                    + "AI output over data they can already read.")
    public ResponseEntity<JsonApiResponse<AiGenerationResponse>> executePrompt(
            @RequestBody ExecuteAiPromptRequest request, Authentication authentication) {
        return ResponseEntity.ok(JsonApiResponse.success(
                aiPromptExecutionService.executePrompt(request, authentication)));
    }

    @PostMapping("/ask")
    @AuthenticatedOnly
    @Operation(summary = "Ask AI a free-form question",
            description = "Free-form instruction over the editor's current content — the \"Ask AI\" box. "
                    + "Same access gate and assessment scoping as running a saved prompt.")
    public ResponseEntity<JsonApiResponse<AiGenerationResponse>> ask(
            @RequestBody AskAiRequest request, Authentication authentication) {
        return ResponseEntity.ok(JsonApiResponse.success(
                aiPromptExecutionService.ask(request, authentication)));
    }

    @PostMapping("/suggest-title")
    @AuthenticatedOnly
    @Operation(summary = "Suggest a title",
            description = "Proposes a short title for the supplied body text, used when creating "
                    + "vulnerabilities and notebook pages.")
    public ResponseEntity<JsonApiResponse<AiGenerationResponse>> suggestTitle(
            @RequestBody SuggestAiTitleRequest request, Authentication authentication) {
        return ResponseEntity.ok(JsonApiResponse.success(
                aiPromptExecutionService.suggestTitle(request, authentication)));
    }
}
