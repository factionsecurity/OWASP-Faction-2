package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assessment_types", indexes = {
    @Index(name = "idx_assessment_types_name", columnList = "name", unique = true)
})
public class AssessmentType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    @Builder.Default
    private Boolean active = true;

    /** The workflow new assessments of this type are created under. */
    @Builder.Default
    @Column(name = "workflow_id", nullable = false)
    @ColumnDefault("'default'")
    private String workflowId = AssessmentWorkflow.DEFAULT_ID;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
