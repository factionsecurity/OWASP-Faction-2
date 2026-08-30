package com.faction.clientportal.dto;

import com.faction.clientportal.model.NotebookAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotebookAttachmentDto {

    private String id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String uploadedById;
    private String uploadedByName;
    private LocalDateTime uploadedAt;

    /** Populated on-demand by the download-url endpoint, not stored */
    private String downloadUrl;

    public static NotebookAttachmentDto fromEntity(NotebookAttachment a) {
        return NotebookAttachmentDto.builder()
                .id(a.getId())
                .fileName(a.getFileName())
                .contentType(a.getContentType())
                .fileSize(a.getFileSize())
                .uploadedById(a.getUploadedById())
                .uploadedByName(a.getUploadedByName())
                .uploadedAt(a.getUploadedAt())
                .build();
    }
}
