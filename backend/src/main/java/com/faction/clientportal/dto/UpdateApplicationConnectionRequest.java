package com.faction.clientportal.dto;

import com.faction.clientportal.model.ConnectionType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateApplicationConnectionRequest {

    @NotNull(message = "Connection type is required")
    private ConnectionType type;

    private String description;
    private Boolean critical;
    private String dataSensitivity; // HIGH, MEDIUM, LOW
}
