package com.faction.clientportal.service;

import com.faction.clientportal.dto.*;
import com.faction.clientportal.exception.ResourceNotFoundException;
import com.faction.clientportal.model.FieldScope;
import com.faction.clientportal.model.FieldType;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.model.UserDefinedField;
import com.faction.clientportal.repository.AssessmentRepository;
import com.faction.clientportal.repository.AssessmentTypeRepository;
import com.faction.clientportal.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTemplateService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final AssessmentTypeRepository assessmentTypeRepository;
    private final AssessmentRepository assessmentRepository;
    private final StorageService storageService;

    private static final long MAX_FILE_SIZE = 1073741824L; // 1GB
    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /**
     * Create a new report template
     */
    /**
     * Starting CSS for newly created report templates. Establishes the report
     * baseline (Arial, paragraph spacing, bounded images, bordered tables,
     * code styling); editable per template in the Report Designer's CSS box.
     */
    static final String DEFAULT_TEMPLATE_CSS = """
            body{
                font-family: Arial;
                font-size: 15px;
            }
            figure{
                text-align: center;
                padding: 0px;
                margin: 10px 0px;
                display: inline-block;
                border: none;
            }
            img{
                max-width: 600px;
                height: auto !important;
                display: block;
                margin: auto !important
            }
            p{
              font-family: Arial;
                padding:0px !important;
                margin:0px !important;
                margin-bottom: 0px !important;
                margin: 10px 0 !important;
            }
            li{
                margin-bottom: 10px !important;
            }
            code {
                font-family: monospace !important;
                color: #666;
                background-color: #eeeeee !important;
                border-radius: 6px !important;
                padding-left: 100px !important;
            }
            code span{
                font-family: monospace!important;
                color: #666;
                background-color: #eeeeee !important;
                border-radius: 6px !important;
            }
            table {
                font-family: Arial;
                border-collapse: collapse;
                width: 100%;
                max-width: 480px;
            }
            td, th {
                font-family: Arial;
                border: 0.3px solid #acb9ca;
                padding-left: 8px;
            }
            td div {
               word-break: break-all !important;
            }
            th {
              white-space: nowrap !important;
              background-color: #afbfcf;
              font-weight: normal;
            }
            pre{
                background-color:#eeeeee !important;
                border:1px solid #cccccc !important;
                font-size:15px;
                padding: 10px 15px;
            }
            """;

    public ReportTemplateDto createReportTemplate(CreateReportTemplateRequest request, String userId) {
        // Verify assessment type exists
        assessmentTypeRepository.findById(request.getAssessmentTypeId())
            .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + request.getAssessmentTypeId()));

        // Check if template name already exists
        if (reportTemplateRepository.existsByName(request.getName())) {
            throw new IllegalArgumentException("Report template with name '" + request.getName() + "' already exists");
        }

        // Validate and generate IDs for user-defined fields
        List<UserDefinedField> fields = validateAndPrepareFields(request.getUserDefinedFields());
        if (fields.isEmpty()) {
            fields = defaultTemplateFields();
        }

        ReportTemplate template = ReportTemplate.builder()
            .name(request.getName())
            .description(request.getDescription())
            .assessmentTypeId(request.getAssessmentTypeId())
            .css(request.getCss() != null && !request.getCss().isBlank()
                    ? request.getCss() : DEFAULT_TEMPLATE_CSS)
            .font(request.getFont())
            .scoringType(request.getScoringType())
            .sections(request.getSections() != null ? new ArrayList<>(request.getSections()) : new ArrayList<>())
            .version(1)
            .userDefinedFields(fields)
            .active(true)
            .createdBy(userId)
            .lastUpdatedBy(userId)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        ReportTemplate savedTemplate = reportTemplateRepository.save(template);
        log.info("Created report template: {} with {} fields", savedTemplate.getName(), fields.size());

        return ReportTemplateDto.fromEntity(savedTemplate);
    }

    /**
     * Duplicate an existing template under a new name — an exact copy of everything that defines the
     * template: description, assessment type, CSS, font, scoring type, sections, every user-defined
     * field (ids and variable names included, so {@code ${...}} references in the DOCX keep working),
     * and the uploaded DOCX itself, copied to its own storage key so the two templates never share a
     * file. The clone starts at version 1 and active: it is a new template, not a revision of the
     * source, and its version tracks its own field edits from here on.
     */
    public ReportTemplateDto cloneReportTemplate(String sourceId, String newName, String userId) {
        ReportTemplate source = reportTemplateRepository.findById(sourceId)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + sourceId));

        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("A name is required for the cloned template");
        }
        String name = newName.trim();
        if (reportTemplateRepository.existsByName(name)) {
            throw new IllegalArgumentException("Report template with name '" + name + "' already exists");
        }

        // Deep-copy the fields: the source's list is a JSON-mapped collection on a managed entity,
        // so reusing its elements would let later edits on either template mutate the other's.
        List<UserDefinedField> fields = source.getUserDefinedFields() == null
            ? new ArrayList<>()
            : source.getUserDefinedFields().stream().map(UserDefinedField::copy).collect(Collectors.toCollection(ArrayList::new));

        ReportTemplate clone = ReportTemplate.builder()
            .name(name)
            .description(source.getDescription())
            .assessmentTypeId(source.getAssessmentTypeId())
            .css(source.getCss())
            .font(source.getFont())
            .scoringType(source.getScoringType())
            .sections(source.getSections() != null ? new ArrayList<>(source.getSections()) : new ArrayList<>())
            .version(1)
            .userDefinedFields(fields)
            .active(true)
            .createdBy(userId)
            .lastUpdatedBy(userId)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        ReportTemplate saved = reportTemplateRepository.save(clone);

        // Copy the DOCX to a key of the clone's own. Best-effort: a storage failure leaves a
        // fileless clone (which the designer handles — you can upload one) rather than losing
        // the whole copy, and never touches the source's object.
        if (source.getTemplateFileId() != null) {
            try {
                byte[] bytes = storageService.downloadBytes(source.getTemplateFileId());
                String key = "report-templates/" + saved.getId() + "/" + source.getTemplateFileName();
                storageService.uploadBytes(key, bytes, source.getTemplateFileContentType());
                saved.setTemplateFileId(key);
                saved.setTemplateFileName(source.getTemplateFileName());
                saved.setTemplateFileSize(source.getTemplateFileSize());
                saved.setTemplateFileContentType(source.getTemplateFileContentType());
                saved = reportTemplateRepository.save(saved);
            } catch (Exception e) {
                log.error("Cloned template {} but failed to copy the DOCX from {}: {}",
                        saved.getId(), source.getTemplateFileId(), e.getMessage(), e);
            }
        }

        log.info("Cloned report template {} -> {} ({} fields)", source.getName(), saved.getName(), fields.size());
        return ReportTemplateDto.fromEntity(saved);
    }

    /**
     * Upload a DOCX template file to MinIO
     */
    public ReportTemplateDto uploadTemplateFile(String templateId, MultipartFile file, String userId) throws IOException {
        ReportTemplate template = reportTemplateRepository.findById(templateId)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + templateId));

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum allowed size of 50MB");
        }

        String contentType = file.getContentType();
        if (!DOCX_CONTENT_TYPE.equals(contentType)) {
            throw new IllegalArgumentException("File must be a DOCX document. Received: " + contentType);
        }

        // Delete old file from MinIO if one exists
        if (template.getTemplateFileId() != null) {
            deleteStorageFile(template.getTemplateFileId());
        }

        // Build object key and upload to MinIO
        String key = "report-templates/" + templateId + "/" + file.getOriginalFilename();
        storageService.uploadBytes(key, file.getBytes(), contentType);

        // Update template with storage reference
        template.setTemplateFileId(key);
        template.setTemplateFileName(file.getOriginalFilename());
        template.setTemplateFileSize(file.getSize());
        template.setTemplateFileContentType(contentType);
        template.setLastUpdatedBy(userId);
        template.setUpdatedAt(LocalDateTime.now());

        ReportTemplate updatedTemplate = reportTemplateRepository.save(template);
        log.info("Uploaded template file for template: {} (key: {})", template.getName(), key);

        return ReportTemplateDto.fromEntity(updatedTemplate);
    }

    /**
     * Download a template file from MinIO, returning the raw bytes.
     */
    public byte[] downloadTemplateFile(String templateId) {
        ReportTemplate template = reportTemplateRepository.findById(templateId)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + templateId));

        if (template.getTemplateFileId() == null) {
            throw new ResourceNotFoundException("Template file not found for template: " + templateId);
        }

        return storageService.downloadBytes(template.getTemplateFileId());
    }

    /**
     * Get a report template by ID
     */
    public ReportTemplateDto getReportTemplate(String id) {
        ReportTemplate template = reportTemplateRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + id));
        return ReportTemplateDto.fromEntity(template);
    }

    /**
     * Update a report template
     */
    public ReportTemplateDto updateReportTemplate(String id, UpdateReportTemplateRequest request, String userId) {
        ReportTemplate template = reportTemplateRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + id));

        boolean fieldsChanged = false;

        // Update name if provided
        if (request.getName() != null) {
            template.setName(request.getName());
        }

        // Update description
        if (request.getDescription() != null) {
            template.setDescription(request.getDescription());
        }

        // Update assessment type
        if (request.getAssessmentTypeId() != null) {
            assessmentTypeRepository.findById(request.getAssessmentTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + request.getAssessmentTypeId()));
            template.setAssessmentTypeId(request.getAssessmentTypeId());
        }

        // Update CSS
        if (request.getCss() != null) {
            template.setCss(request.getCss());
        }

        // Update report font
        if (request.getFont() != null) {
            template.setFont(request.getFont());
        }

        // Update scoring type
        if (request.getScoringType() != null) {
            template.setScoringType(request.getScoringType());
        }

        // Update fields and increment version if structure changed
        if (request.getUserDefinedFields() != null) {
            List<UserDefinedField> newFields = validateAndPrepareFieldsForUpdate(request.getUserDefinedFields());

            // Version increments only for structural changes (type, variable name, required, add/remove)
            // Must check BEFORE overwriting the template's field list
            if (!fieldsEqual(template.getUserDefinedFields(), newFields)) {
                template.setVersion(template.getVersion() + 1);
                fieldsChanged = true;
                log.info("Template {} fields changed, incrementing version to {}", template.getName(), template.getVersion());
            }

            // Always persist field data (defaultValue, dropdownOptions, helpText, etc.)
            template.setUserDefinedFields(newFields);
        }

        // Update sections
        if (request.getSections() != null) {
            template.setSections(new ArrayList<>(request.getSections()));
        }

        // Update active status
        if (request.getActive() != null) {
            template.setActive(request.getActive());
        }

        template.setLastUpdatedBy(userId);
        template.setUpdatedAt(LocalDateTime.now());

        ReportTemplate updatedTemplate = reportTemplateRepository.save(template);
        log.info("Updated report template: {} (version: {})", updatedTemplate.getName(), updatedTemplate.getVersion());

        return ReportTemplateDto.fromEntity(updatedTemplate);
    }

    /**
     * Delete a report template
     * Soft delete if assessments exist, hard delete otherwise
     */
    public Map<String, String> deleteReportTemplate(String id) {
        ReportTemplate template = reportTemplateRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Report template not found with id: " + id));

        boolean hasAssessments = assessmentRepository.existsByReportTemplateIdAndDeletedAtIsNull(id);

        if (hasAssessments) {
            // Soft delete
            template.setActive(false);
            template.setDeletedAt(LocalDateTime.now());
            reportTemplateRepository.save(template);
            log.info("Soft deleted report template: {} (has existing assessments)", template.getName());
            return Map.of(
                "status", "deactivated",
                "message", "Template deactivated because it is used by existing assessments. The template data is preserved."
            );
        } else {
            // Hard delete
            if (template.getTemplateFileId() != null) {
                deleteStorageFile(template.getTemplateFileId());
            }
            reportTemplateRepository.delete(template);
            log.info("Hard deleted report template: {} (no assessments)", template.getName());
            return Map.of(
                "status", "deleted",
                "message", "Template permanently deleted."
            );
        }
    }

    /**
     * Search report templates with pagination
     */
    public Page<ReportTemplateSummaryDto> searchReportTemplates(
        String name,
        String assessmentTypeId,
        Boolean active,
        Pageable pageable
    ) {
        Page<ReportTemplate> templates;

        if (name != null && !name.isEmpty()) {
            templates = reportTemplateRepository.searchByName(name, pageable);
        } else if (assessmentTypeId != null && (active == null || active)) {
            templates = reportTemplateRepository.findByAssessmentTypeIdAndActiveTrue(assessmentTypeId, pageable);
        } else if (active != null && active) {
            templates = reportTemplateRepository.findByActiveTrue(pageable);
        } else {
            templates = reportTemplateRepository.findAll(pageable);
        }

        return templates.map(ReportTemplateSummaryDto::fromEntity);
    }

    /**
     * Collect all VULNERABILITY-scoped user-defined fields across all active templates.
     * Deduplicates by variableName, keeping the first occurrence encountered.
     */
    public List<UserDefinedFieldDto> getVulnerabilityFields() {
        List<ReportTemplate> templates = reportTemplateRepository.findByActiveTrue(Pageable.unpaged()).getContent();
        Map<String, UserDefinedFieldDto> byVariableName = new LinkedHashMap<>();
        for (ReportTemplate template : templates) {
            if (template.getUserDefinedFields() == null) continue;
            for (UserDefinedField field : template.getUserDefinedFields()) {
                if (com.faction.clientportal.model.FieldScope.VULNERABILITY.equals(field.getFieldScope())) {
                    byVariableName.putIfAbsent(field.getVariableName(), UserDefinedFieldDto.fromEntity(field));
                }
            }
        }
        return new ArrayList<>(byVariableName.values());
    }

    /**
     * Get all templates for a specific assessment type
     */
    public List<ReportTemplateSummaryDto> getTemplatesByAssessmentType(String assessmentTypeId) {
        // Verify assessment type exists
        assessmentTypeRepository.findById(assessmentTypeId)
            .orElseThrow(() -> new ResourceNotFoundException("Assessment type not found with id: " + assessmentTypeId));

        return reportTemplateRepository.findByAssessmentTypeIdAndActiveTrue(assessmentTypeId, Pageable.unpaged())
            .stream()
            .map(ReportTemplateSummaryDto::fromEntity)
            .collect(Collectors.toList());
    }

    // Helper methods

    /**
     * Validate field definitions and generate UUIDs for fields without IDs (strict validation for creation)
     */
    /**
     * Starting fields for a template created without any: the two rich-text
     * summary sections every report leads with. Their variable names
     * (summary1/summary2) are recognized by the DOCX generator's built-in
     * ${summary1}/${summary2} placeholders.
     */
    private List<UserDefinedField> defaultTemplateFields() {
        List<UserDefinedField> fields = new ArrayList<>();
        fields.add(UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .variableName("summary1")
                .displayName("Executive Summary")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.ASSESSMENT)
                .displayOrder(0)
                .build());
        fields.add(UserDefinedField.builder()
                .id(UUID.randomUUID().toString())
                .variableName("summary2")
                .displayName("Scope")
                .fieldType(FieldType.RICH_TEXT)
                .fieldScope(FieldScope.ASSESSMENT)
                .displayOrder(1)
                .build());
        return fields;
    }

    private List<UserDefinedField> validateAndPrepareFields(List<UserDefinedFieldDto> fieldDtos) {
        if (fieldDtos == null || fieldDtos.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> variableNames = new HashSet<>();
        List<UserDefinedField> fields = new ArrayList<>();

        for (UserDefinedFieldDto dto : fieldDtos) {
            // Generate ID if not provided
            if (dto.getId() == null || dto.getId().isEmpty()) {
                dto.setId(UUID.randomUUID().toString());
            }

            // Validate variable name uniqueness
            if (!variableNames.add(dto.getVariableName())) {
                throw new IllegalArgumentException("Duplicate variable name: " + dto.getVariableName());
            }

            fields.add(dto.toEntity());
        }

        return fields;
    }

    /**
     * Validate and prepare fields for update (lenient validation)
     * Allows empty lists, duplicate variable names, and blank names during editing
     */
    private List<UserDefinedField> validateAndPrepareFieldsForUpdate(List<UserDefinedFieldDto> fieldDtos) {
        if (fieldDtos == null) {
            return new ArrayList<>();
        }

        List<UserDefinedField> fields = new ArrayList<>();

        for (UserDefinedFieldDto dto : fieldDtos) {
            // Generate ID if not provided
            if (dto.getId() == null || dto.getId().isEmpty()) {
                dto.setId(UUID.randomUUID().toString());
            }

            // Allow duplicate and blank variable names during editing
            // The frontend or user will fix these before using the template

            fields.add(dto.toEntity());
        }

        return fields;
    }

    /**
     * Check if two field lists are equal (ignoring order)
     */
    private boolean fieldsEqual(List<UserDefinedField> fields1, List<UserDefinedField> fields2) {
        if (fields1 == null || fields2 == null) {
            return fields1 == fields2;
        }

        if (fields1.size() != fields2.size()) {
            return false;
        }

        // Create maps for comparison
        Map<String, UserDefinedField> map1 = fields1.stream()
            .collect(Collectors.toMap(UserDefinedField::getId, f -> f));
        Map<String, UserDefinedField> map2 = fields2.stream()
            .collect(Collectors.toMap(UserDefinedField::getId, f -> f));

        if (!map1.keySet().equals(map2.keySet())) {
            return false;
        }

        // Compare each field's properties (including displayOrder)
        for (String id : map1.keySet()) {
            UserDefinedField f1 = map1.get(id);
            UserDefinedField f2 = map2.get(id);

            if (!Objects.equals(f1.getVariableName(), f2.getVariableName()) ||
                !Objects.equals(f1.getDisplayName(), f2.getDisplayName()) ||
                !Objects.equals(f1.getFieldType(), f2.getFieldType()) ||
                !Objects.equals(f1.getRequired(), f2.getRequired()) ||
                !Objects.equals(f1.getDisplayOrder(), f2.getDisplayOrder())) {
                return false;
            }
        }

        // Also detect positional reordering — if the sequence of IDs changed the fields
        // were dragged into a new order even if displayOrder values are identical.
        List<String> order1 = fields1.stream().map(UserDefinedField::getId).collect(Collectors.toList());
        List<String> order2 = fields2.stream().map(UserDefinedField::getId).collect(Collectors.toList());
        if (!order1.equals(order2)) {
            return false;
        }

        return true;
    }

    /**
     * Delete a file from MinIO storage.
     */
    private void deleteStorageFile(String key) {
        try {
            storageService.deleteObject(key);
            log.info("Deleted storage file: {}", key);
        } catch (Exception e) {
            log.error("Error deleting storage file: {}", key, e);
            // Don't throw — continue with template deletion
        }
    }
}
