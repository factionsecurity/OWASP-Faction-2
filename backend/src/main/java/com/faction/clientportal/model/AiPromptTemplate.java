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
 * Admin-defined AI prompt offered in rich text editors (e.g. "Executive Summary").
 * The prompt text is the instruction sent to the model; the model can pull
 * assessment-scoped data through the internal tool calls.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_prompt_template")
public class AiPromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Name shown in the editor's AI menu, e.g. "Executive Summary" */
    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiPromptScope scope;

    /** The admin-written instruction sent to the model */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String prompt;

    /** Optional pinned provider; null = use the default enabled provider */
    private String providerId;

    /** Optional pinned model; null = the provider's default model */
    private String model;

    /** When true, this prompt may search the web and fetch URLs (e.g. to add reference links) */
    @Column(nullable = false)
    @Builder.Default
    private boolean allowWebAccess = false;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
