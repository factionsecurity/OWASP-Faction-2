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
@Table(name = "survey_templates")
public class SurveyTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<SurveyTemplateQuestion> questions;

    private boolean active;

    private String createdBy;

    private String lastUpdatedBy;

    private Instant createdAt;

    private Instant updatedAt;
}
