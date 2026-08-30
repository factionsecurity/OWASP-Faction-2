package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A hierarchical notebook node tied to an Application.
 * Supports multi-level note-taking with file attachments and an audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notebook_nodes", indexes = {
    @Index(name = "idx_notebook_nodes_applicationid", columnList = "application_id"),
    @Index(name = "idx_notebook_nodes_app_parent_order", columnList = "application_id, parent_id, order_index, deleted_at"),
    @Index(name = "idx_notebook_nodes_assessment_parent", columnList = "assessment_id, parent_id, deleted_at"),
    @Index(name = "idx_notebook_nodes_app_deleted", columnList = "application_id, deleted_at")
})
public class NotebookNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Anchor — every node is scoped to an Application */
    private String applicationId;

    /** Which assessment created/owns this root node (nullable) */
    private String assessmentId;

    /** null = top-level root node under application */
    private String parentId;

    private String title;

    /** HTML from RichTextEditor */
    private String content;

    /** HTML-stripped plain text for full-text search */
    private String contentText;

    /** Sibling ordering */
    private int orderIndex;

    /** 0 = root; enforces MAX_DEPTH limit */
    private int depth;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<NotebookAttachment> attachments = new ArrayList<>();

    private LocalDateTime createdAt;
    private String createdById;
    private String createdByName;

    private LocalDateTime lastModifiedAt;

    /** Audit trail of all edits */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ModificationRecord> modifiedBy = new ArrayList<>();

    /** Soft delete timestamp */
    private LocalDateTime deletedAt;
}
