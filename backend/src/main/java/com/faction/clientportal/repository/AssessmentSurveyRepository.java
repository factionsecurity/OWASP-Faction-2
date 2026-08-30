package com.faction.clientportal.repository;

import com.faction.clientportal.model.AssessmentSurvey;
import com.faction.clientportal.model.SurveyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AssessmentSurveyRepository extends JpaRepository<AssessmentSurvey, String> {

    List<AssessmentSurvey> findByAssessmentId(String assessmentId);

    /**
     * Assessments carrying at least one survey that is not finished — what the "Open Surveys"
     * filter narrows the assessments list to. JPQL, so the enum's storage mapping stays the
     * persistence layer's business rather than being hard-coded into a native query.
     */
    @Query("SELECT DISTINCT s.assessmentId FROM AssessmentSurvey s WHERE s.status <> :status")
    List<String> findAssessmentIdsWithStatusNot(SurveyStatus status);
}
