package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.AddAssessmentSurveyRequest;
import com.faction.clientportal.dto.AssessmentSurveyDto;
import com.faction.clientportal.dto.UpdateAssessmentSurveyRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.AssessmentSurveyService;
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
@RequestMapping("/api/v1/assessments/{assessmentId}/surveys")
@RequiredArgsConstructor
@Tag(name = "Assessment Surveys", description = "Assessment survey management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentSurveyController {

    private final AssessmentSurveyService service;

    @GetMapping
    @AuthenticatedOnly
    @Operation(summary = "Get surveys for an assessment")
    public ResponseEntity<JsonApiResponse<List<AssessmentSurveyDto>>> getByAssessment(
            @PathVariable String assessmentId,
            Authentication authentication) {
        return ResponseUtil.success("Assessment surveys retrieved successfully",
                service.getByAssessment(assessmentId, authentication));
    }

    @PostMapping
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_SELF})
    @Operation(summary = "Add a survey to an assessment")
    public ResponseEntity<JsonApiResponse<AssessmentSurveyDto>> addToAssessment(
            @PathVariable String assessmentId,
            @Valid @RequestBody AddAssessmentSurveyRequest request,
            Authentication authentication) {
        return ResponseUtil.created("Assessment survey added successfully",
                service.addToAssessment(assessmentId, request, authentication.getName()));
    }

    @PutMapping("/{surveyId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_SELF, Permission.SURVEYS_COMPLETE})
    @Operation(summary = "Update survey responses")
    public ResponseEntity<JsonApiResponse<AssessmentSurveyDto>> updateSurvey(
            @PathVariable String assessmentId,
            @PathVariable String surveyId,
            @RequestBody UpdateAssessmentSurveyRequest request,
            Authentication authentication) {
        return ResponseUtil.success("Assessment survey updated successfully",
                service.updateSurvey(assessmentId, surveyId, request, authentication.getName(), authentication));
    }

    @DeleteMapping("/{surveyId}")
    @RequiresPermission({Permission.ASSESSMENTS_EDIT_ALL, Permission.ASSESSMENTS_EDIT_TEAM, Permission.ASSESSMENTS_EDIT_SELF})
    @Operation(summary = "Remove a survey from an assessment")
    public ResponseEntity<Void> removeFromAssessment(
            @PathVariable String assessmentId,
            @PathVariable String surveyId) {
        service.removeFromAssessment(assessmentId, surveyId);
        return ResponseEntity.noContent().build();
    }
}
