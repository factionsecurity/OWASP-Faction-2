package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Singleton config for reversible anonymization of AI content. When enabled,
 * secrets/PII are masked with placeholders before any text is sent to the LLM
 * provider, and the real values are restored in the generated output.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_anonymization_config")
public class AiAnonymizationConfig {

    public static final String SINGLETON_ID = "singleton";

    @Id
    @Builder.Default
    private String id = SINGLETON_ID;

    @Builder.Default
    private boolean enabled = false;

    /**
     * Presidio analyzer base URL (e.g. http://localhost:5002) for PII detection.
     * Optional — when blank, only the built-in secret patterns are used.
     */
    private String presidioUrl;

    /** Minimum Presidio confidence score to mask an entity */
    @Column(nullable = false)
    @Builder.Default
    private double scoreThreshold = 0.5;
}
