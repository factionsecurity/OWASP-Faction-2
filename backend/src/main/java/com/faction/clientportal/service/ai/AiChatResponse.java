package com.faction.clientportal.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** One model turn: either final text content or a set of tool calls to execute. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {
    private String content;
    private List<AiToolCall> toolCalls;
    /** What the provider reported this turn cost; {@link AiTokenUsage#NONE} if it reported nothing. */
    private AiTokenUsage usage = AiTokenUsage.NONE;

    public AiChatResponse(String content, List<AiToolCall> toolCalls) {
        this(content, toolCalls, AiTokenUsage.NONE);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
