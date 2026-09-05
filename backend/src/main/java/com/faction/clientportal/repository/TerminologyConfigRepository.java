package com.faction.clientportal.repository;

import com.faction.clientportal.model.TerminologyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TerminologyConfigRepository extends JpaRepository<TerminologyConfig, String> {
}
