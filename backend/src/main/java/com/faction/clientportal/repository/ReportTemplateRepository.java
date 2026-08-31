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
     * Find all active templates.
     *
     * <p>Every listing method here excludes soft-deleted rows. Deletion is soft so existing
     * assessments keep resolving the template they were generated from — but a deleted
     * template must not be offered for anything new. Leaving them in the listing is how a
     * caller ends up holding the id of a template nobody can see in the UI.
     */
    Page<ReportTemplate> findByActiveTrueAndDeletedAtIsNull(Pageable pageable);

    /**
     * Find templates by assessment type ID
     */
    Page<ReportTemplate> findByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNull(String assessmentTypeId, Pageable pageable);

    /**
     * Every template still in play, whatever its active flag.
     */
    Page<ReportTemplate> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Search templates by name (case-insensitive, partial match)
     */
    @Query("SELECT r FROM ReportTemplate r WHERE LOWER(r.name) LIKE LOWER(CONCAT(?1, '%')) "
         + "AND r.active = true AND r.deletedAt IS NULL")
    Page<ReportTemplate> searchByName(String namePattern, Pageable pageable);

    /** Lookup that refuses to hand back a deleted template. */
    Optional<ReportTemplate> findByIdAndDeletedAtIsNull(String id);

    /**
     * Find template by name and exclude soft-deleted
     */
    Optional<ReportTemplate> findByNameAndDeletedAtIsNull(String name);

    /**
     * Count templates by assessment type (active only)
     */
    long countByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNull(String assessmentTypeId);
}
