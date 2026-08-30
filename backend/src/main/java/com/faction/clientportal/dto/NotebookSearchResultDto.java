package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotebookSearchResultDto {

    private NotebookNodeDto node;

    /** Titles from the root node down to (and including) this node */
    private List<String> breadcrumb;

    /** Title of the assessment root node, if applicable */
    private String assessmentName;
}
