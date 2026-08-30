package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "organizations", indexes = {
    @Index(name = "idx_organizations_name", columnList = "name", unique = true)
})
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    // Custom field values (keyed by field definition ID)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> fieldValues = new HashMap<>();

    // Assigned portal users (owners)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<AssignedUser> assignedUsers = new ArrayList<>();
}
