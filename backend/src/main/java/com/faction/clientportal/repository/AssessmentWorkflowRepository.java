package com.faction.clientportal.repository;

import com.faction.clientportal.model.AssessmentWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssessmentWorkflowRepository extends JpaRepository<AssessmentWorkflow, String> {
}
