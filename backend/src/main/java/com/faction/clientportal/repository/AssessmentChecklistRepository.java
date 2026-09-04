package com.faction.clientportal.repository;

import com.faction.clientportal.model.AssessmentChecklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssessmentChecklistRepository extends JpaRepository<AssessmentChecklist, String> {

    List<AssessmentChecklist> findByAssessmentId(String assessmentId);

}
