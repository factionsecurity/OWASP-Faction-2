package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.CreateSurveyTemplateRequest;
import com.faction.clientportal.dto.SurveyTemplateDto;
import com.faction.clientportal.dto.UpdateSurveyTemplateRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.SurveyTemplateService;
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
@RequestMapping("/api/v1/survey-templates")
@RequiredArgsConstructor
@Tag(name = "Survey Templates", description = "Survey template management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class SurveyTemplateController {

    private final SurveyTemplateService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get all survey templates")
    public ResponseEntity<JsonApiResponse<List<SurveyTemplateDto>>> getAll(
            @RequestParam(required = false) Boolean active) {
        List<SurveyTemplateDto> result = Boolean.TRUE.equals(active)
                ? service.getActive()
                : service.getAll();
        return ResponseUtil.success("Survey templates retrieved successfully", result);
    }

    @GetMapping("/{id}")
    @AuthenticatedOnly
    @Operation(summary = "Get survey template by ID")
    public ResponseEntity<JsonApiResponse<SurveyTemplateDto>> getById(@PathVariable String id) {
        return ResponseUtil.success("Survey template retrieved successfully", service.getById(id));
    }

    @PostMapping
    @RequiresPermission(Permission.SURVEY_TEMPLATES_CREATE)
    @Operation(summary = "Create survey template")
    public ResponseEntity<JsonApiResponse<SurveyTemplateDto>> create(
            @Valid @RequestBody CreateSurveyTemplateRequest request,
            Authentication authentication) {
        return ResponseUtil.created("Survey template created successfully",
                service.create(request, authentication.getName()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.SURVEY_TEMPLATES_EDIT)
    @Operation(summary = "Update survey template")
    public ResponseEntity<JsonApiResponse<SurveyTemplateDto>> update(
            @PathVariable String id,
            @RequestBody UpdateSurveyTemplateRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Survey template updated successfully",
                service.update(id, request, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.SURVEY_TEMPLATES_DELETE)
    @Operation(summary = "Delete survey template")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
