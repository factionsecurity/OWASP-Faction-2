package com.faction.clientportal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReorderVulnerabilitiesRequest {

    @NotNull
    @Valid
    private List<VulnerabilityOrderItem> order;

    @Data
    public static class VulnerabilityOrderItem {

        @NotBlank(message = "Vulnerability ID is required")
        private String id;

        @NotNull(message = "Order value is required")
        @Min(value = 0, message = "Order must be a non-negative integer")
        private Integer order;
    }
}
