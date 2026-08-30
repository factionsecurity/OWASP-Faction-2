package com.faction.clientportal.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "application_id_config")
public class ApplicationIdConfig {

    @Id
    private String id;

    private String prefix;

    private Integer nextNumber;

    private Integer padding;

    private Boolean enabled;

    private String createdBy;

    private String lastUpdatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
