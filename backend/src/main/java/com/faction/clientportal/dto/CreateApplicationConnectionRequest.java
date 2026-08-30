package com.faction.clientportal.dto;

import com.faction.clientportal.model.ConnectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateApplicationConnectionRequest {

    @NotBlank(message = "Source application ID is required")
    private String sourceApplicationId;

    @NotBlank(message = "Target application ID is required")
    private String targetApplicationId;

    @NotNull(message = "Connection type is required")
    private ConnectionType type;

    private String description;
    private Boolean critical;
    private String dataSensitivity; // HIGH, MEDIUM, LOW
}
