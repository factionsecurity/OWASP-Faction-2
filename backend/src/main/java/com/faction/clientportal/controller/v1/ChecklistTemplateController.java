package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.ChecklistTemplateDto;
import com.faction.clientportal.dto.CreateChecklistTemplateRequest;
import com.faction.clientportal.dto.UpdateChecklistTemplateRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.ChecklistTemplateService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/checklist-templates")
@RequiredArgsConstructor
@Tag(name = "Checklist Templates", description = "Checklist template management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ChecklistTemplateController {

    private final ChecklistTemplateService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get all checklist templates")
    public ResponseEntity<JsonApiResponse<List<ChecklistTemplateDto>>> getAll(
            @RequestParam(required = false) String assessmentTypeId) {
        List<ChecklistTemplateDto> result = assessmentTypeId != null
                ? service.getByAssessmentType(assessmentTypeId)
                : service.getAll();
        return ResponseUtil.success("Checklist templates retrieved successfully", result);
    }

    @GetMapping("/{id}")
    @AuthenticatedOnly
    @Operation(summary = "Get checklist template by ID")
    public ResponseEntity<JsonApiResponse<ChecklistTemplateDto>> getById(@PathVariable String id) {
        return ResponseUtil.success("Checklist template retrieved successfully", service.getById(id));
    }

    @PostMapping
    @RequiresPermission(Permission.CHECKLIST_TEMPLATES_CREATE)
    @Operation(summary = "Create checklist template")
    public ResponseEntity<JsonApiResponse<ChecklistTemplateDto>> create(
            @Valid @RequestBody CreateChecklistTemplateRequest request,
            Authentication authentication) {
        return ResponseUtil.created("Checklist template created successfully",
                service.create(request, authentication.getName()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CHECKLIST_TEMPLATES_EDIT)
    @Operation(summary = "Update checklist template")
    public ResponseEntity<JsonApiResponse<ChecklistTemplateDto>> update(
            @PathVariable String id,
            @RequestBody UpdateChecklistTemplateRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Checklist template updated successfully",
                service.update(id, request, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.CHECKLIST_TEMPLATES_DELETE)
    @Operation(summary = "Delete checklist template")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
