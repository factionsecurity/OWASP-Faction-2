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
 * Stores global custom field definitions for a given entity scope (APPLICATION, ORGANIZATION).
 * One document per scope — all instances of that entity type share these field definitions
 * and store their own values in Application.fieldValues / Organization.fieldValues.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "entity_field_configs", indexes = {
    @Index(name = "idx_entity_field_configs_scope", columnList = "scope", unique = true)
})
public class EntityFieldConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private FieldScope scope;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UserDefinedField> fieldDefinitions = new ArrayList<>();

    private String lastUpdatedBy;
    private LocalDateTime updatedAt;
}
