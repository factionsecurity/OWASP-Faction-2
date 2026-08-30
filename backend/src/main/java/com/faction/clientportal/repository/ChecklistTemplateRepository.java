package com.faction.clientportal.repository;

import com.faction.clientportal.model.ChecklistTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, String> {

    List<ChecklistTemplate> findByAssessmentTypeIdAndActiveTrue(String assessmentTypeId);

    List<ChecklistTemplate> findAllByOrderByCreatedAtDesc();
}
