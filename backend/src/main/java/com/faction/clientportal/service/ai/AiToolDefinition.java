package com.faction.clientportal.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic tool definition. {@code properties} is a JSON-Schema
 * property map, e.g. {"query": {"type": "string", "description": "..."}}.
 */
@Data
@AllArgsConstructor
public class AiToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> properties;
    private List<String> required;
}
