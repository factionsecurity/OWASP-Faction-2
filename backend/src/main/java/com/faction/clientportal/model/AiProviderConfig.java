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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_provider_config")
public class AiProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Admin-chosen display name, e.g. "OpenAI (prod)" */
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiProviderType providerType;

    /** Optional override; falls back to the provider's default. Required for AZURE_OPENAI / OPENAI_COMPATIBLE. */
    private String baseUrl;

    /** AES-GCM encrypted API key */
    private String encryptedApiKey;

    /** Azure OpenAI api-version query parameter */
    private String apiVersion;

    /** Models enabled for this provider (fetched from the endpoint or entered manually) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<String> models = new ArrayList<>();

    /** Preferred model for this provider; must be one of {@link #models} */
    private String defaultModel;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
