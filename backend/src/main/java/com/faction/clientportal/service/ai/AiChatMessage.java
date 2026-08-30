package com.faction.clientportal.service.ai;

import lombok.Data;

import java.util.List;

/** Provider-agnostic chat message for the tool-use loop. */
@Data
public class AiChatMessage {

    public enum Role { USER, ASSISTANT, TOOL_RESULT }

    private Role role;
    /** Text content (USER and plain ASSISTANT messages) */
    private String content;
    /** Tool calls requested by the model (ASSISTANT messages) */
    private List<AiToolCall> toolCalls;
    /** For TOOL_RESULT: the id/name of the call this result answers, and its output */
    private String toolCallId;
    private String toolName;
    private String toolResult;

    public static AiChatMessage user(String content) {
        AiChatMessage m = new AiChatMessage();
        m.role = Role.USER;
        m.content = content;
        return m;
    }

    public static AiChatMessage assistantToolCalls(List<AiToolCall> toolCalls) {
        AiChatMessage m = new AiChatMessage();
        m.role = Role.ASSISTANT;
        m.toolCalls = toolCalls;
        return m;
    }

    public static AiChatMessage toolResult(String toolCallId, String toolName, String result) {
        AiChatMessage m = new AiChatMessage();
        m.role = Role.TOOL_RESULT;
        m.toolCallId = toolCallId;
        m.toolName = toolName;
        m.toolResult = result;
        return m;
    }
}
