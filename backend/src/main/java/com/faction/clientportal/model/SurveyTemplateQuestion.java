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
public class SurveyTemplateQuestion {
    private String id;
    private String text;
    private SurveyFieldType fieldType;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> dropdownOptions;
    private int order;
}
