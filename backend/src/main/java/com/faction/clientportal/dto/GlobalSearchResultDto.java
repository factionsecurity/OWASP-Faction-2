package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Global search's answer: one group per kind of record the caller may read, in a fixed order,
 * each holding its first few matches and how many matched in all. A kind the caller may not read
 * is left out rather than returned empty, so the panel never advertises a category they cannot see.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchResultDto {

    /** The text that was searched, trimmed. */
    private String query;

    @Builder.Default
    private List<Group> groups = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Group {
        /** {@code vulnerabilities}, {@code assessments}, {@code applications}, {@code users} or
         *  {@code assessmentTypes}. */
        private String type;
        /** Everything that matched; more than {@code items} holds when the group was cut short. */
        private long total;
        @Builder.Default
        private List<Item> items = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String id;
        private String title;
        /** Where it sits or how to tell it apart, e.g. "VULN-12 · Payments API · Q3 Pentest". */
        private String subtitle;
        /** A short label shown beside the title: severity, status, or the record's own id. */
        private String badge;
        /** For a vulnerability, its assessment, which the details panel loads it through. */
        private String assessmentId;
    }
}
