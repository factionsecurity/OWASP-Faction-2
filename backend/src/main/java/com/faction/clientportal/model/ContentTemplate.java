package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Reusable boilerplate offered in rich text editors (e.g. "Standard Testing Methodology").
 * The content is HTML, and the user chooses at insert time whether it overwrites,
 * prepends to, or appends to whatever the editor already holds.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "content_template")
public class ContentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Title shown in the editor's template picker, e.g. "Standard Testing Methodology" */
    @Column(nullable = false)
    private String name;

    /** Optional one-line note shown under the title in the picker */
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentTemplateScope scope;

    /** The template body — HTML, the same shape RichTextEditor stores */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Disabled templates stay editable on the admin page but disappear from the picker */
    @Builder.Default
    private boolean enabled = true;

    /** Username of the author, shown on the admin list */
    private String createdBy;

    private String lastUpdatedBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
