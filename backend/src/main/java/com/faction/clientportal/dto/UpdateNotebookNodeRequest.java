package com.faction.clientportal.dto;

import lombok.Data;

@Data
public class UpdateNotebookNodeRequest {

    private String title;
    private String content;
    private Integer orderIndex;
}
