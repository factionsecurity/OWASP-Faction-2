package com.faction.clientportal.repository;

import com.faction.clientportal.model.AssessmentWorkflowConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssessmentWorkflowConfigRepository extends JpaRepository<AssessmentWorkflowConfig, String> {
}
