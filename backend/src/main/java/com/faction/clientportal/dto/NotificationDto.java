package com.faction.clientportal.dto;

import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.Notification;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationDto {

    private String id;
    private String username;
    private String title;
    private String message;
    private String type;
    private String link;
    private MentionTargetType targetType;
    private String targetId;
    private String targetName;
    private String actorUsername;
    private String actorName;
    private String excerpt;
    private boolean read;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public static NotificationDto fromEntity(Notification n) {
        NotificationDto dto = new NotificationDto();
        dto.setId(n.getId());
        dto.setUsername(n.getUsername());
        dto.setTitle(n.getTitle());
        dto.setMessage(n.getMessage());
        dto.setType(n.getType());
        dto.setLink(n.getLink());
        dto.setTargetType(n.getTargetType());
        dto.setTargetId(n.getTargetId());
        dto.setTargetName(n.getTargetName());
        dto.setActorUsername(n.getActorUsername());
        dto.setActorName(n.getActorName());
        dto.setExcerpt(n.getExcerpt());
        dto.setRead(n.isRead());
        dto.setReadAt(n.getReadAt());
        dto.setCreatedAt(n.getCreatedAt());
        return dto;
    }
}
