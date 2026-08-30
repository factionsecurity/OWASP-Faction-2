package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One generated report artifact (DOCX, PDF, or password-protected PDF) for an
 * assessment. A single row exists per (assessment, docType) pair and is
 * upserted on every generation run so the frontend can show per-document
 * progress and last-generated timestamps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "report_documents",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_report_documents_assessment_type",
               columnNames = {"assessment_id", "doc_type"}),
       indexes = @Index(name = "idx_report_documents_assessment",
               columnList = "assessment_id"))
public class ReportDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "assessment_id", nullable = false)
    private String assessmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 32)
    private ReportDocumentType docType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportDocumentStatus status;

    /**
     * Storage (MinIO) key of the generated file. Null until the first
     * successful generation; retains the previous key while a regeneration is
     * in flight so the last good file stays downloadable.
     */
    private String fileId;

    /** Timestamp of the last successful generation of this document. */
    private LocalDateTime generatedAt;

    /** Failure detail from the last run, null when the last run succeeded. */
    @Column(length = 2000)
    private String errorMessage;

    private LocalDateTime updatedAt;
}
