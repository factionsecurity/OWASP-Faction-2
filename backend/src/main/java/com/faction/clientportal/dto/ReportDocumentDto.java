package com.faction.clientportal.dto;

import com.faction.clientportal.model.ReportDocument;
import com.faction.clientportal.model.ReportDocumentStatus;
import com.faction.clientportal.model.ReportDocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Status of one generated report artifact, as shown in the Finalize
 * "Report Documents" panel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDocumentDto {

    private ReportDocumentType type;
    private ReportDocumentStatus status;

    /** True when a stored file exists and can be downloaded. */
    private boolean available;

    private LocalDateTime generatedAt;
    private String errorMessage;

    public static ReportDocumentDto fromEntity(ReportDocument doc) {
        return ReportDocumentDto.builder()
                .type(doc.getDocType())
                .status(doc.getStatus())
                .available(doc.getFileId() != null)
                .generatedAt(doc.getGeneratedAt())
                .errorMessage(doc.getErrorMessage())
                .build();
    }
}
