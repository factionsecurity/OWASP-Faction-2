package com.faction.clientportal.repository;

import com.faction.clientportal.model.AssessmentWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssessmentWorkflowRepository extends JpaRepository<AssessmentWorkflow, String> {

    /**
     * Whether another workflow (not this id) already has this name, ignoring case. The name index is
     * case-sensitive, so two differently-cased names can both exist; a finder would then throw
     * {@code IncorrectResultSizeDataAccessException} instead of answering the question.
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, String id);

    boolean existsByNameIgnoreCase(String name);
}
