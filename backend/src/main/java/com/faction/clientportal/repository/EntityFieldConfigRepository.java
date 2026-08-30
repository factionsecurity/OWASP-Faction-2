package com.faction.clientportal.repository;

import com.faction.clientportal.model.EntityFieldConfig;
import com.faction.clientportal.model.FieldScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EntityFieldConfigRepository extends JpaRepository<EntityFieldConfig, String> {
    Optional<EntityFieldConfig> findByScope(FieldScope scope);
}
