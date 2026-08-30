package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * All generated report artifacts for an assessment plus the password for the
 * encrypted PDF variant (decrypted for display in the Finalize panel).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDocumentsDto {

    private List<ReportDocumentDto> documents;

    /** Open password for the encrypted PDF; null until one has been provisioned. */
    private String reportPassword;
}
