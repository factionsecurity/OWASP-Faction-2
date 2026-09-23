package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Dry run of an assessment CSV import: what each row would create, and what stops it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentImportPreviewDto {

    @Builder.Default
    private List<Row> rows = new ArrayList<>();
    private int total;
    private int validCount;
    private int errorCount;
    private int newApplicationCount;
    private int newCampaignCount;
    /** True only when every row is valid — the import refuses otherwise. */
    private boolean valid;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        /** 1-based file line; the header is line 1. */
        private int line;
        private String name;
        private String application;
        private boolean newApplication;
        private String assessmentType;
        private LocalDate startDate;
        private LocalDate endDate;
        @Builder.Default
        private List<String> assessors = new ArrayList<>();
        private String campaign;
        private boolean newCampaign;
        private String team;
        @Builder.Default
        private List<String> errors = new ArrayList<>();
    }
}
