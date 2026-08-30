package com.faction.clientportal.dto;

import com.faction.clientportal.model.AssessmentFile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentFileDto {

    private String id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String uploadedBy;
    private String uploadedByName;
    private LocalDateTime uploadedAt;

    /** Populated on-demand by the download-url endpoint, not stored */
    private String downloadUrl;

    public static AssessmentFileDto fromEntity(AssessmentFile f) {
        return AssessmentFileDto.builder()
                .id(f.getId())
                .fileName(f.getFileName())
                .contentType(f.getContentType())
                .fileSize(f.getFileSize())
                .uploadedBy(f.getUploadedBy())
                .uploadedByName(f.getUploadedByName())
                .uploadedAt(f.getUploadedAt())
                .build();
    }
}
