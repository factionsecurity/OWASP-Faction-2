package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "checklist_templates", indexes = {
    @Index(name = "idx_checklist_templates_assessmenttype_active", columnList = "assessment_type_id, active")
})
public class ChecklistTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    private String assessmentTypeId;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ChecklistTemplateQuestion> questions;

    private boolean active;

    private boolean preventClosure;

    private String createdBy;

    private String lastUpdatedBy;

    private Instant createdAt;

    private Instant updatedAt;
}
