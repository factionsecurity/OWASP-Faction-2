package com.faction.clientportal.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A tool invocation requested by the model. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiToolCall {
    private String id;
    private String name;
    /** Raw JSON string of the tool arguments */
    private String argumentsJson;
}
