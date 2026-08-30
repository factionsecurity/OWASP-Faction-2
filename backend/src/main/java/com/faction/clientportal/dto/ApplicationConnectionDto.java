package com.faction.clientportal.dto;

import com.faction.clientportal.model.ConnectionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationConnectionDto {

    private String id;
    private String sourceApplicationId;
    private String sourceApplicationName;
    private String targetApplicationId;
    private String targetApplicationName;
    private ConnectionType type;
    private String description;
    private Boolean critical;
    private String dataSensitivity;
    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
