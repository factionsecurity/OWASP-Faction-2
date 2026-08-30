package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.AiPromptTemplateDto;
import com.faction.clientportal.dto.SaveAiPromptTemplateRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.AiPromptTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ai-config/prompts")
@RequiredArgsConstructor
@Tag(name = "AI Prompt Templates", description = "Admin-defined AI prompts for rich text editors")
public class AiPromptTemplateController {

    private final AiPromptTemplateService aiPromptTemplateService;

    @GetMapping
    @RequiresPermission({Permission.AI_CONFIG_READ, Permission.AI_CONFIG_WRITE})
    @Operation(summary = "List AI prompt templates",
            description = "Every configured prompt, enabled or not — the admin view. Editors see only the "
                    + "enabled ones, via GET /api/v1/ai/prompts.")
    public ResponseEntity<JsonApiResponse<List<AiPromptTemplateDto>>> getPrompts() {
        return ResponseEntity.ok(JsonApiResponse.success(aiPromptTemplateService.getPrompts()));
    }

    @PostMapping
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Create an AI prompt template",
            description = "Adds a prompt to the editor AI menu for the scope it declares.")
    public ResponseEntity<JsonApiResponse<AiPromptTemplateDto>> createPrompt(
            @RequestBody SaveAiPromptTemplateRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(aiPromptTemplateService.createPrompt(request)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Update an AI prompt template",
            description = "Replaces the prompt's name, scope, instruction text, and enabled flag.")
    public ResponseEntity<JsonApiResponse<AiPromptTemplateDto>> updatePrompt(
            @PathVariable String id, @RequestBody SaveAiPromptTemplateRequest request) {
        return ResponseEntity.ok(JsonApiResponse.success(aiPromptTemplateService.updatePrompt(id, request)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.AI_CONFIG_WRITE)
    @Operation(summary = "Delete an AI prompt template",
            description = "Removes the prompt. Editors stop offering it immediately; past runs stay in the "
                    + "AI request log.")
    public ResponseEntity<JsonApiResponse<Void>> deletePrompt(@PathVariable String id) {
        aiPromptTemplateService.deletePrompt(id);
        return ResponseEntity.ok(JsonApiResponse.success("AI prompt deleted", null));
    }
}
