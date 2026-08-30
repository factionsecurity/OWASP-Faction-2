package com.faction.clientportal.dto;

import com.faction.clientportal.model.ApplicationComment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationCommentDto {

    private String id;
    private String authorId;
    private String authorName;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean systemGenerated;

    public static ApplicationCommentDto fromEntity(ApplicationComment comment) {
        return ApplicationCommentDto.builder()
                .id(comment.getId())
                .authorId(comment.getAuthorId())
                .authorName(comment.getAuthorName())
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .systemGenerated(comment.isSystemGenerated())
                .build();
    }
}
