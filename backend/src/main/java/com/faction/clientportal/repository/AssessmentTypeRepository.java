package com.faction.clientportal.repository;

import com.faction.clientportal.model.AssessmentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentTypeRepository extends JpaRepository<AssessmentType, String> {

    Optional<AssessmentType> findByName(String name);

    boolean existsByName(String name);

    List<AssessmentType> findAllByNameIgnoreCase(String name);

    @Query("SELECT a FROM AssessmentType a WHERE LOWER(a.name) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(a.description) LIKE LOWER(CONCAT(?1, '%'))")
    Page<AssessmentType> searchByNameOrDescription(String searchTerm, Pageable pageable);

    /** Assessment types assigned to one workflow: the archive and delete guards. */
    long countByWorkflowId(String workflowId);

    /** Assessment type counts per workflow, as {@code [workflowId, count]} rows, for the admin usage view. */
    @Query("select t.workflowId, count(t) from AssessmentType t group by t.workflowId")
    List<Object[]> countGroupedByWorkflowId();
}
