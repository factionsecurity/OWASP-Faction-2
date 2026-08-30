package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Entity;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Table;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Id;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "region_config")
public class RegionConfig {

    @Id
    private String id;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> regions;
}
