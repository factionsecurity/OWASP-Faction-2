package com.faction.clientportal.controller.v1;

import com.faction.clientportal.security.AuthenticatedOnly;
import com.faction.clientportal.util.PageableUtil;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.security.RequiresPermission;
import com.faction.clientportal.dto.*;
import com.faction.clientportal.dto.common.JsonApiResponse;
import com.faction.clientportal.service.ReportTemplateService;
import com.faction.clientportal.util.ResponseUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/report-templates")
@RequiredArgsConstructor
@Tag(name = "Report Templates", description = "Report template management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class ReportTemplateController {

    private final ReportTemplateService reportTemplateService;

    @PostMapping
    @RequiresPermission(Permission.REPORT_TEMPLATES_CREATE_ALL)
    @Operation(
        summary = "Create report template",
        description = "Create a new report template with user-defined fields",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report template created successfully",
                content = @Content(schema = @Schema(implementation = ReportTemplateDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request - validation error"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<ReportTemplateDto>> createReportTemplate(
        @Valid @RequestBody CreateReportTemplateRequest request,
        Authentication authentication
    ) {
        String userId = authentication.getName();
        ReportTemplateDto template = reportTemplateService.createReportTemplate(request, userId);
        return ResponseUtil.success("Report template created successfully", template);
    }

    @PostMapping("/{id}/clone")
    @RequiresPermission(Permission.REPORT_TEMPLATES_CREATE_ALL)
    @Operation(
        summary = "Clone report template",
        description = "Duplicate an existing template under a new name. Everything that defines the "
            + "template is copied exactly — description, assessment type, CSS, font, scoring type, "
            + "sections, every user-defined field (ids and variable names included, so the DOCX's "
            + "${...} references still resolve) and the uploaded DOCX itself, copied to its own "
            + "storage key. The clone starts at version 1 and is active.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report template cloned successfully",
                content = @Content(schema = @Schema(implementation = ReportTemplateDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Name missing or already in use"),
            @ApiResponse(responseCode = "404", description = "Source template not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<ReportTemplateDto>> cloneReportTemplate(
        @PathVariable String id,
        @Valid @RequestBody CloneReportTemplateRequest request,
        Authentication authentication
    ) {
        String userId = authentication.getName();
        ReportTemplateDto template = reportTemplateService.cloneReportTemplate(id, request.getName(), userId);
        return ResponseUtil.success("Report template cloned successfully", template);
    }

    @PostMapping("/{id}/file")
    @RequiresPermission(Permission.REPORT_TEMPLATES_EDIT_ALL)
    @Operation(
        summary = "Upload template file",
        description = "Upload a DOCX template file to S3/MinIO. Maximum file size: 50MB",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Template file uploaded successfully",
                content = @Content(schema = @Schema(implementation = ReportTemplateDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid file - must be DOCX and under 50MB"),
            @ApiResponse(responseCode = "404", description = "Report template not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<ReportTemplateDto>> uploadTemplateFile(
        @PathVariable String id,
        @RequestParam("file") MultipartFile file,
        Authentication authentication
    ) throws IOException {
        String userId = authentication.getName();
        ReportTemplateDto template = reportTemplateService.uploadTemplateFile(id, file, userId);
        return ResponseUtil.success("Template file uploaded successfully", template);
    }

    @GetMapping("/{id}/file")
    @RequiresPermission(Permission.REPORT_TEMPLATES_READ_ALL)
    @Operation(
        summary = "Download template file",
        description = "Download the DOCX template file from S3/MinIO",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Template file downloaded successfully",
                content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            ),
            @ApiResponse(responseCode = "404", description = "Report template or file not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<Resource> downloadTemplateFile(@PathVariable String id) throws IOException {
        ReportTemplateDto template = reportTemplateService.getReportTemplate(id);
        byte[] bytes = reportTemplateService.downloadTemplateFile(id);

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + template.getTemplateFileName() + "\"")
            .body(new ByteArrayResource(bytes));
    }

    @GetMapping
    @RequiresPermission(Permission.REPORT_TEMPLATES_READ_ALL)
    @Operation(
        summary = "Search report templates",
        description = "Search report templates with pagination and filters",
        parameters = {
            @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
            @Parameter(name = "size", description = "Number of items per page", example = "10"),
            @Parameter(name = "name", description = "Filter by template name (case-insensitive)", example = "Pentest"),
            @Parameter(name = "assessmentTypeId", description = "Filter by assessment type ID"),
            @Parameter(name = "active", description = "Filter by active status", example = "true"),
            @Parameter(name = "sort", description = "Sort field and direction", example = "name,asc")
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved report templates",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<ReportTemplateSummaryDto>>> searchReportTemplates(
        @Parameter(hidden = true) @RequestParam(defaultValue = "0") int page,
        @Parameter(hidden = true) @RequestParam(defaultValue = "10") int size,
        @Parameter(hidden = true) @RequestParam(required = false) String name,
        @Parameter(hidden = true) @RequestParam(required = false) String assessmentTypeId,
        @Parameter(hidden = true) @RequestParam(required = false) Boolean active,
        @Parameter(hidden = true) @RequestParam(defaultValue = "name,asc") String sort
    ) {
        Pageable pageable = PageableUtil.of(page, size, sort);
        Page<ReportTemplateSummaryDto> templates = reportTemplateService.searchReportTemplates(
            name, assessmentTypeId, active, pageable
        );

        return ResponseUtil.paginated("Report templates retrieved successfully", templates);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.REPORT_TEMPLATES_READ_ALL)
    @Operation(
        summary = "Get report template by ID",
        description = "Retrieve a single report template with full details",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved report template",
                content = @Content(schema = @Schema(implementation = ReportTemplateDto.class))
            ),
            @ApiResponse(responseCode = "404", description = "Report template not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<ReportTemplateDto>> getReportTemplate(@PathVariable String id) {
        ReportTemplateDto template = reportTemplateService.getReportTemplate(id);
        return ResponseUtil.success("Report template retrieved successfully", template);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.REPORT_TEMPLATES_EDIT_ALL)
    @Operation(
        summary = "Update report template",
        description = "Update an existing report template. Version increments if fields are modified.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report template updated successfully",
                content = @Content(schema = @Schema(implementation = ReportTemplateDto.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request - validation error"),
            @ApiResponse(responseCode = "404", description = "Report template not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<ReportTemplateDto>> updateReportTemplate(
        @PathVariable String id,
        @Valid @RequestBody UpdateReportTemplateRequest request,
        Authentication authentication
    ) {
        String userId = authentication.getName();
        ReportTemplateDto template = reportTemplateService.updateReportTemplate(id, request, userId);
        return ResponseUtil.success("Report template updated successfully", template);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.REPORT_TEMPLATES_DELETE_ALL)
    @Operation(
        summary = "Delete report template",
        description = "Delete a report template. Soft delete if assessments exist, hard delete otherwise.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Report template deleted/deactivated successfully"
            ),
            @ApiResponse(responseCode = "404", description = "Report template not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<Map<String, String>>> deleteReportTemplate(@PathVariable String id) {
        Map<String, String> result = reportTemplateService.deleteReportTemplate(id);
        return ResponseUtil.success(result.get("message"), result);
    }

    @GetMapping("/vulnerability-fields")
    @AuthenticatedOnly
    @Operation(
        summary = "Get vulnerability-scoped fields",
        description = "Retrieve all VULNERABILITY-scoped user-defined fields across all active report templates, deduplicated by variable name",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved vulnerability fields"
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<UserDefinedFieldDto>>> getVulnerabilityFields() {
        List<UserDefinedFieldDto> fields = reportTemplateService.getVulnerabilityFields();
        return ResponseUtil.success("Vulnerability fields retrieved successfully", fields);
    }

    @GetMapping("/by-assessment-type/{assessmentTypeId}")
    @RequiresPermission(Permission.REPORT_TEMPLATES_READ_ALL)
    @Operation(
        summary = "Get templates by assessment type",
        description = "Retrieve all active templates for a specific assessment type",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Successfully retrieved templates",
                content = @Content(schema = @Schema(implementation = ReportTemplateSummaryDto.class))
            ),
            @ApiResponse(responseCode = "404", description = "Assessment type not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not have required permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token")
        }
    )
    public ResponseEntity<JsonApiResponse<List<ReportTemplateSummaryDto>>> getTemplatesByAssessmentType(
        @PathVariable String assessmentTypeId
    ) {
        List<ReportTemplateSummaryDto> templates = reportTemplateService.getTemplatesByAssessmentType(assessmentTypeId);
        return ResponseUtil.success("Templates retrieved successfully", templates);
    }
}
