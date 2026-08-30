package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.AddAssessmentChecklistRequest;
import com.faction.clientportal.dto.AssessmentChecklistDto;
import com.faction.clientportal.dto.UpdateAssessmentChecklistRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.AssessmentChecklistService;
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
@RequestMapping("/api/v1/assessments/{assessmentId}/checklists")
@RequiredArgsConstructor
@Tag(name = "Assessment Checklists", description = "Assessment checklist management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentChecklistController {

    private final AssessmentChecklistService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get checklists for an assessment")
    public ResponseEntity<JsonApiResponse<List<AssessmentChecklistDto>>> getByAssessment(
            @PathVariable String assessmentId) {
        return ResponseUtil.success("Assessment checklists retrieved successfully",
                service.getByAssessment(assessmentId));
    }

    @PostMapping
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_SELF})
    @Operation(summary = "Add a checklist to an assessment")
    public ResponseEntity<JsonApiResponse<AssessmentChecklistDto>> addToAssessment(
            @PathVariable String assessmentId,
            @Valid @RequestBody AddAssessmentChecklistRequest request,
            Authentication authentication) {
        return ResponseUtil.created("Assessment checklist added successfully",
                service.addToAssessment(assessmentId, request, authentication.getName()));
    }

    @PutMapping("/{checklistId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_SELF})
    @Operation(summary = "Update checklist responses")
    public ResponseEntity<JsonApiResponse<AssessmentChecklistDto>> updateResponses(
            @PathVariable String assessmentId,
            @PathVariable String checklistId,
            @RequestBody UpdateAssessmentChecklistRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Assessment checklist updated successfully",
                service.updateResponses(assessmentId, checklistId, request, authentication.getName()));
    }

    @DeleteMapping("/{checklistId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_SELF})
    @Operation(summary = "Remove a checklist from an assessment")
    public ResponseEntity<Void> removeFromAssessment(
            @PathVariable String assessmentId,
            @PathVariable String checklistId) {
        service.removeFromAssessment(assessmentId, checklistId);
        return ResponseEntity.noContent().build();
    }
}
