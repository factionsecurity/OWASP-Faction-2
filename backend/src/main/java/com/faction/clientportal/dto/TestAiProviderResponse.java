package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestAiProviderResponse {
    private boolean success;
    private String message;
    private String details;
    /** Models reported by the endpoint when the connection succeeds */
    private List<AiModelInfo> models;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiModelInfo {
        private String id;
        private String name;
    }
}
