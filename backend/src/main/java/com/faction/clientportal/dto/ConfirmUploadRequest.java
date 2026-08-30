package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ConfirmUploadRequest {
    @NotBlank
    private String fileId;
    @NotBlank
    private String fileName;
    @NotBlank
    private String contentType;
    @Positive
    private Long fileSize;
}
