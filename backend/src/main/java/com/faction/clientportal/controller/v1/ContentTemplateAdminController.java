package com.faction.clientportal.controller.v1;

import com.faction.clientportal.dto.ContentTemplateDto;
import com.faction.clientportal.dto.SaveContentTemplateRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.service.ContentTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/content-templates")
@RequiredArgsConstructor
@Tag(name = "Content Templates", description = "Reusable rich text boilerplate for editors")
public class ContentTemplateAdminController {

    private final ContentTemplateService contentTemplateService;

    @GetMapping
    @RequiresPermission({Permission.CONTENT_TEMPLATES_CREATE, Permission.CONTENT_TEMPLATES_EDIT,
            Permission.CONTENT_TEMPLATES_DELETE})
    @Operation(summary = "List content templates",
            description = "Every template, enabled or not — the management view. Editors see only the "
                    + "enabled ones for their scope, via GET /api/v1/content-templates.")
    public ResponseEntity<JsonApiResponse<List<ContentTemplateDto>>> getTemplates() {
        return ResponseEntity.ok(JsonApiResponse.success(contentTemplateService.getTemplates()));
    }

    @PostMapping
    @RequiresPermission(Permission.CONTENT_TEMPLATES_CREATE)
    @Operation(summary = "Create a content template",
            description = "Adds a template to the picker of every editor in the scope it declares.")
    public ResponseEntity<JsonApiResponse<ContentTemplateDto>> createTemplate(
            @RequestBody SaveContentTemplateRequest request, Authentication authentication) {
        return ResponseEntity.ok(JsonApiResponse.success(
                contentTemplateService.createTemplate(request, authentication.getName())));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CONTENT_TEMPLATES_EDIT)
    @Operation(summary = "Update a content template",
            description = "Replaces the template's title, description, scope, body, and enabled flag.")
    public ResponseEntity<JsonApiResponse<ContentTemplateDto>> updateTemplate(
            @PathVariable String id, @RequestBody SaveContentTemplateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(JsonApiResponse.success(
                contentTemplateService.updateTemplate(id, request, authentication.getName())));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.CONTENT_TEMPLATES_DELETE)
    @Operation(summary = "Delete a content template",
            description = "Removes the template. Editors stop offering it immediately; text already "
                    + "inserted from it is untouched.")
    public ResponseEntity<JsonApiResponse<Void>> deleteTemplate(@PathVariable String id) {
        contentTemplateService.deleteTemplate(id);
        return ResponseEntity.ok(JsonApiResponse.success("Content template deleted", null));
    }
}
