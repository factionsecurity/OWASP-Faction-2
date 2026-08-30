package com.faction.clientportal.model;

import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurveyResponse {
    private String questionId;
    private String questionText;
    private SurveyFieldType fieldType;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> dropdownOptions;
    private String answer;
    private int order;
}
