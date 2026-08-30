package com.faction.clientportal.dto;

import com.faction.clientportal.model.ExtensionLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A log line produced by an extension. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtensionLogDto {

    private String id;
    private String level;
    private String eventType;
    private String message;
    private String stackTrace;
    private LocalDateTime timestamp;

    public static ExtensionLogDto from(ExtensionLog log) {
        return ExtensionLogDto.builder()
                .id(log.getId())
                .level(log.getLevel())
                .eventType(log.getEventType())
                .message(log.getMessage())
                .stackTrace(log.getStackTrace())
                .timestamp(log.getTimestamp())
                .build();
    }
}
