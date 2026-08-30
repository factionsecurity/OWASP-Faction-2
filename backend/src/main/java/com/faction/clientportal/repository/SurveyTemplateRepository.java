package com.faction.clientportal.repository;

import com.faction.clientportal.model.SurveyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SurveyTemplateRepository extends JpaRepository<SurveyTemplate, String> {

    List<SurveyTemplate> findAllByOrderByCreatedAtDesc();

    List<SurveyTemplate> findByActiveTrueOrderByCreatedAtDesc();
}
