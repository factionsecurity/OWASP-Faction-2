package com.faction.clientportal.dto;

import com.faction.clientportal.model.NotebookNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotebookNodeDto {

    private String id;
    private String applicationId;
    private String assessmentId;
    private String parentId;
    private String title;
    private String content;
    private int orderIndex;
    private int depth;

    @Builder.Default
    private List<NotebookAttachmentDto> attachments = new ArrayList<>();

    private LocalDateTime createdAt;
    private String createdById;
    private String createdByName;
    private LocalDateTime lastModifiedAt;

    @Builder.Default
    private List<ModificationRecordDto> modifiedBy = new ArrayList<>();

    /** Populated when the tree is loaded; empty for single-node responses */
    @Builder.Default
    private List<NotebookNodeDto> children = new ArrayList<>();

    /** True if the node has at least one non-deleted child */
    private boolean hasChildren;

    public static NotebookNodeDto fromEntity(NotebookNode node) {
        return NotebookNodeDto.builder()
                .id(node.getId())
                .applicationId(node.getApplicationId())
                .assessmentId(node.getAssessmentId())
                .parentId(node.getParentId())
                .title(node.getTitle())
                .content(node.getContent())
                .orderIndex(node.getOrderIndex())
                .depth(node.getDepth())
                .attachments(node.getAttachments() == null ? new ArrayList<>() :
                        node.getAttachments().stream()
                                .map(NotebookAttachmentDto::fromEntity)
                                .collect(Collectors.toList()))
                .createdAt(node.getCreatedAt())
                .createdById(node.getCreatedById())
                .createdByName(node.getCreatedByName())
                .lastModifiedAt(node.getLastModifiedAt())
                .modifiedBy(node.getModifiedBy() == null ? new ArrayList<>() :
                        node.getModifiedBy().stream()
                                .map(ModificationRecordDto::fromEntity)
                                .collect(Collectors.toList()))
                .children(new ArrayList<>())
                .hasChildren(false)
                .build();
    }
}
