package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Report Template entity for defining reusable assessment report templates.
 * Templates include DOCX files (stored in S3/MinIO), CSS styling, and custom field definitions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "report_templates", indexes = {
    @Index(name = "idx_report_templates_name", columnList = "name", unique = true),
    @Index(name = "idx_report_templates_assessmenttypeid", columnList = "assessment_type_id"),
    @Index(name = "idx_report_templates_active", columnList = "active")
})
public class ReportTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * Unique name of the template
     */
    @Column(unique = true, nullable = false)
    private String name;

    /**
     * Description of what this template is for
     */
    private String description;

    /**
     * Reference to the AssessmentType this template is used for
     */
    private String assessmentTypeId;

    /**
     * CSS styling for report generation (stored directly as string)
     */
    @Column(columnDefinition = "TEXT")
    private String css;

    /**
     * Font family applied to rich-text content in the generated DOCX
     * (feeds DocxUtils.FONT). Empty/null keeps the template's own fonts.
     */
    private String font;

    /**
     * S3/MinIO file ID reference for the DOCX template file
     */
    private String templateFileId;

    /**
     * Original filename of the uploaded DOCX file
     */
    private String templateFileName;

    /**
     * Size of the template file in bytes
     */
    private Long templateFileSize;

    /**
     * Content type of the template file (should be application/vnd.openxmlformats-officedocument.wordprocessingml.document)
     */
    private String templateFileContentType;

    /**
     * Scoring type used for vulnerabilities: NATIVE, CVSS_31, or CVSS_40
     */
    private String scoringType;

    /**
     * Version number of this template.
     * Increments when field definitions are modified.
     * Allows tracking which template version an assessment used.
     */
    @Builder.Default
    private Integer version = 1;

    /**
     * Report sections (ordered list of section name strings).
     * When defined, vulnerabilities can be assigned to a section for grouped DOCX output.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> sections = new ArrayList<>();

    /**
     * User-defined fields that assessors will fill in
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UserDefinedField> userDefinedFields = new ArrayList<>();

    /**
     * Whether this template is active and can be used for new assessments
     */
    @Builder.Default
    private Boolean active = true;

    // Audit fields
    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Soft delete timestamp - set when template is deactivated but has existing assessments
     */
    private LocalDateTime deletedAt;
}
