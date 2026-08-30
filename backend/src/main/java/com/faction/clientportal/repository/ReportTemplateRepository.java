package com.faction.clientportal.repository;

import com.faction.clientportal.model.ReportTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for ReportTemplate entity.
 * Provides data access methods for report template management.
 */
@Repository
public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, String> {

    /**
     * Find a template by exact name match
     */
    Optional<ReportTemplate> findByName(String name);

    /**
     * Check if a template with the given name exists
     */
    boolean existsByName(String name);

    /**
     * Find all active templates
     */
    Page<ReportTemplate> findByActiveTrue(Pageable pageable);

    /**
     * Find templates by assessment type ID
     */
    Page<ReportTemplate> findByAssessmentTypeIdAndActiveTrue(String assessmentTypeId, Pageable pageable);

    /**
     * Search templates by name (case-insensitive, partial match)
     */
    @Query("SELECT r FROM ReportTemplate r WHERE LOWER(r.name) LIKE LOWER(CONCAT(?1, '%')) AND r.active = true")
    Page<ReportTemplate> searchByName(String namePattern, Pageable pageable);

    /**
     * Find template by name and exclude soft-deleted
     */
    Optional<ReportTemplate> findByNameAndDeletedAtIsNull(String name);

    /**
     * Count templates by assessment type (active only)
     */
    long countByAssessmentTypeIdAndActiveTrue(String assessmentTypeId);
}
