package com.faction.clientportal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateNotebookNodeRequest {

    @NotBlank
    private String title;

    /** Optional HTML content; defaults to empty string */
    private String content;

    /** null for a top-level root node */
    private String parentId;

    /** Sibling ordering; defaults to 0 */
    private int orderIndex;
}
