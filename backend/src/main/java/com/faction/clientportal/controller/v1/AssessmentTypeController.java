package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.util.PageableUtil.SortField;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.AssessmentTypeDto;
import com.faction.clientportal.dto.CreateAssessmentTypeRequest;
import com.faction.clientportal.dto.UpdateAssessmentTypeRequest;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.AssessmentTypeService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/assessment-types")
@RequiredArgsConstructor
@Tag(name = "Assessment Types", description = "Assessment type management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AssessmentTypeController {

    private final AssessmentTypeService assessmentTypeService;

    /** Sortable assessment-type columns. */
    private static final Map<String, SortField> SORTABLE_FIELDS = Map.of(
            "name", SortField.text("name"),
            "description", SortField.text("description"),
            "active", SortField.value("active"),
            "createdAt", SortField.value("createdAt"));

    private static final Sort DEFAULT_SORT = Sort.by("name");

    @GetMapping
    @AuthenticatedOnly
    @Operation(
            summary = "Get all assessment types",
            description = "Retrieves all assessment types with pagination support and optional search by name or description (case-insensitive)"
    )
    public ResponseEntity<JsonApiResponse<List<AssessmentTypeDto>>> getAllAssessmentTypes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name,asc") String sort,
            @Parameter(description = "Search term to filter assessment types by name or description (case-insensitive)")
            @RequestParam(required = false) String search) {

        Pageable pageable = PageableUtil.of(page, size, sort, DEFAULT_SORT, SORTABLE_FIELDS);

        Page<AssessmentTypeDto> assessmentTypes = assessmentTypeService.searchAssessmentTypes(search, pageable);
        return ResponseUtil.paginated("Assessment types retrieved successfully", assessmentTypes);
    }

    @GetMapping("/{id}")
    @AuthenticatedOnly
    @Operation(
            summary = "Get assessment type by ID",
            description = "Retrieves a specific assessment type by its ID"
    )
    public ResponseEntity<JsonApiResponse<AssessmentTypeDto>> getAssessmentTypeById(@PathVariable String id) {
        AssessmentTypeDto assessmentType = assessmentTypeService.getAssessmentTypeById(id);
        return ResponseUtil.success("Assessment type retrieved successfully", assessmentType);
    }

    @PostMapping
    @RequiresPermission(Permission.ASSESSMENTS_CREATE_ALL)
    @Operation(
            summary = "Create assessment type",
            description = "Creates a new assessment type. Requires super_admin or assessments:create:all permission."
    )
    public ResponseEntity<JsonApiResponse<AssessmentTypeDto>> createAssessmentType(
            @Valid @RequestBody CreateAssessmentTypeRequest request) {
        AssessmentTypeDto assessmentType = assessmentTypeService.createAssessmentType(request);
        return ResponseUtil.created("Assessment type created successfully", assessmentType);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.ASSESSMENTS_EDIT_ALL)
    @Operation(
            summary = "Update assessment type",
            description = "Updates an existing assessment type. Requires super_admin or assessments:edit:all permission."
    )
    public ResponseEntity<JsonApiResponse<AssessmentTypeDto>> updateAssessmentType(
            @PathVariable String id,
            @Valid @RequestBody UpdateAssessmentTypeRequest request) {
        AssessmentTypeDto assessmentType = assessmentTypeService.updateAssessmentType(id, request);
        return ResponseUtil.success("Assessment type updated successfully", assessmentType);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.ASSESSMENTS_DELETE_ALL)
    @Operation(
            summary = "Delete or deactivate assessment type",
            description = "Deletes an assessment type if not in use, otherwise deactivates it. " +
                    "Requires super_admin or assessments:delete:all permission."
    )
    public ResponseEntity<JsonApiResponse<Void>> deleteAssessmentType(@PathVariable String id) {
        boolean wasDeleted = assessmentTypeService.deleteAssessmentType(id);

        if (wasDeleted) {
            return ResponseUtil.success("Assessment type deleted successfully");
        } else {
            return ResponseUtil.success("Assessment type is in use and has been deactivated instead");
        }
    }
}
