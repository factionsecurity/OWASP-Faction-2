package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "application_connections", indexes = {
    @Index(name = "idx_application_connections_source_target", columnList = "source_application_id, target_application_id", unique = true),
    @Index(name = "idx_application_connections_source", columnList = "source_application_id"),
    @Index(name = "idx_application_connections_target", columnList = "target_application_id")
})
public class ApplicationConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String sourceApplicationId;
    private String targetApplicationId;

    private ConnectionType type;
    private String description;

    // For threat modeling
    private Boolean critical;
    private String dataSensitivity; // HIGH, MEDIUM, LOW

    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
