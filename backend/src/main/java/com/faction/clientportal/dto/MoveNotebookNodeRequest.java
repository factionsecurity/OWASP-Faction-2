package com.faction.clientportal.dto;

import lombok.Data;

@Data
public class MoveNotebookNodeRequest {

    /** null = make the node a top-level root */
    private String newParentId;

    private int newOrderIndex;
}
