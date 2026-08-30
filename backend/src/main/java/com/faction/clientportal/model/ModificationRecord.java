package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Audit record tracking a single modification to a notebook node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModificationRecord {

    private String userId;
    private String userName;
    private LocalDateTime modifiedAt;
}
