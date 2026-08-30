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
@Table(name = "assessment_checklists", indexes = {
    @Index(name = "idx_assessment_checklists_assessmentid", columnList = "assessment_id")
})
public class AssessmentChecklist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String assessmentId;

    private String templateId;

    private String templateName;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ChecklistResponse> responses;

    private String createdBy;

    private String lastUpdatedBy;

    private Instant createdAt;

    private Instant updatedAt;
}
