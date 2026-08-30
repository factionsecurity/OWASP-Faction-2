package com.faction.clientportal.repository;

import com.faction.clientportal.model.RegionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegionConfigRepository extends JpaRepository<RegionConfig, String> {
}
