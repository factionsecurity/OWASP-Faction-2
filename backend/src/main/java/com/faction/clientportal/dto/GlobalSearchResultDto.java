package com.faction.clientportal.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Global search's answer: one group per kind of record the caller may read, in a fixed order,
 * each holding its first few matches and how many matched in all. A kind the caller may not read
 * is left out rather than returned empty, so the panel never advertises a category they cannot see.
 *
 * <p>Immutable: the lists are copied in, so nothing that holds the answer can change it.
 */
@Value
@Builder
public class GlobalSearchResultDto {

    /** The text that was searched, trimmed. */
    String query;

    List<Group> groups;

    public GlobalSearchResultDto(String query, List<Group> groups) {
        this.query = query;
        this.groups = groups == null ? List.of() : List.copyOf(groups);
    }

    @Value
    @Builder
    public static class Group {
        /** {@code vulnerabilities}, {@code assessments}, {@code applications}, {@code users} or
         *  {@code assessmentTypes}. */
        String type;
        /** Everything that matched; more than {@code items} holds when the group was cut short. */
        long total;
        List<Item> items;

        public Group(String type, long total, List<Item> items) {
            this.type = type;
            this.total = total;
            this.items = items == null ? List.of() : List.copyOf(items);
        }
    }

    @Value
    @Builder
    public static class Item {
        String id;
        String title;
        /** Where it sits or how to tell it apart, e.g. "VULN-12 · Payments API · Q3 Pentest". */
        String subtitle;
        /** A short label shown beside the title: severity, status, or the record's own id. */
        String badge;
        /** For a vulnerability, its assessment, which the details panel loads it through. */
        String assessmentId;
    }
}
