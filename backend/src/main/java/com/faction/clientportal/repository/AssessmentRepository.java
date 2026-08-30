package com.faction.clientportal.repository;

import com.faction.clientportal.model.Assessment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Assessment entity.
 * Provides data access methods for assessment management.
 */
@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, String>, AssessmentRepositoryCustom {

    /**
     * Find assessments by application ID
     */
    Page<Assessment> findByApplicationIdAndDeletedAtIsNull(String applicationId, Pageable pageable);

    List<Assessment> findByApplicationIdAndDeletedAtIsNull(String applicationId);

    /**
     * Find assessments by organization ID
     */
    Page<Assessment> findByOrganizationIdAndDeletedAtIsNull(String organizationId, Pageable pageable);

    /**
     * Find assessments by status
     */
    Page<Assessment> findByStatusAndDeletedAtIsNull(String status, Pageable pageable);

    /**
     * Find assessments by application and assessment type
     */
    Page<Assessment> findByApplicationIdAndAssessmentTypeIdAndDeletedAtIsNull(
        String applicationId,
        String assessmentTypeId,
        Pageable pageable
    );

    /**
     * Check if any non-deleted assessments exist for a report template
     */
    boolean existsByReportTemplateIdAndDeletedAtIsNull(String reportTemplateId);

    /**
     * Check if any non-deleted assessments reference a campaign (campaign delete-guard)
     */
    boolean existsByCampaignIdAndDeletedAtIsNull(String campaignId);

    /**
     * Count assessments completed within a period (manager dashboard stats)
     */
    long countByCompletedDateBetweenAndDeletedAtIsNull(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /**
     * Find all assessments using a specific report template
     */
    List<Assessment> findByReportTemplateIdAndDeletedAtIsNull(String reportTemplateId);

    /**
     * Find assessments by assessor
     */
    Page<Assessment> findByAssessorIdAndDeletedAtIsNull(String assessorId, Pageable pageable);

    /**
     * Search assessments by name (case-insensitive, partial match)
     */
    @Query("SELECT a FROM Assessment a WHERE LOWER(a.name) LIKE LOWER(CONCAT(?1, '%')) AND a.deletedAt IS NULL")
    Page<Assessment> searchByName(String namePattern, Pageable pageable);

    /**
     * Find by ID excluding soft-deleted
     */
    Optional<Assessment> findByIdAndDeletedAtIsNull(String id);

    /**
     * Count assessments by status for an organization
     */
    long countByOrganizationIdAndStatusAndDeletedAtIsNull(String organizationId, String status);

    /**
     * Count assessments by application
     */
    long countByApplicationIdAndDeletedAtIsNull(String applicationId);

    /**
     * Find assessments by date range (for calendar view)
     */
    @Query("SELECT a FROM Assessment a WHERE a.startDate <= ?2 AND a.plannedEndDate >= ?1 AND a.deletedAt IS NULL")
    Page<Assessment> findByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    /**
     * Find assessments by multiple assessors (supports new assessorIds array)
     */
    @Query(value = "SELECT * FROM assessments WHERE assessor_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) AND deleted_at IS NULL",
           countQuery = "SELECT count(*) FROM assessments WHERE assessor_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) AND deleted_at IS NULL",
           nativeQuery = true)
    Page<Assessment> findByAssessorIdsContaining(String assessorId, Pageable pageable);

    /**
     * Find assessments by engagement manager
     */
    Page<Assessment> findByEngagementManagerIdAndDeletedAtIsNull(String engagementManagerId, Pageable pageable);

    /**
     * Find assessments by remediation manager
     */
    Page<Assessment> findByRemediationManagerIdAndDeletedAtIsNull(String remediationManagerId, Pageable pageable);

    /**
     * Find conflicting assessments by assessors and date range
     * Used for conflict detection when scheduling
     */
    @Query(value = "SELECT * FROM assessments a WHERE EXISTS (" +
                   "SELECT 1 FROM jsonb_array_elements_text(a.assessor_ids) ae " +
                   "WHERE ae IN (SELECT jsonb_array_elements_text(CAST(?1 AS jsonb)))) " +
                   "AND a.start_date < ?3 AND a.planned_end_date > ?2 AND a.deleted_at IS NULL", nativeQuery = true)
    List<Assessment> findConflictingByAssessors(String assessorIdsJson, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Find past due assessments (plannedEndDate before currentDate, not deleted).
     * Filtering by completed status is done in the service using workflow config.
     */
    @Query("SELECT a FROM Assessment a WHERE a.plannedEndDate < ?1 AND a.deletedAt IS NULL")
    List<Assessment> findPastDue(LocalDateTime currentDate);

    /**
     * Count assessments by status (excluding deleted)
     */
    long countByStatusAndDeletedAtIsNull(String status);

    /**
     * Count all non-deleted assessments
     */
    long countByDeletedAtIsNull();

    /**
     * All non-deleted assessment counts grouped by status. Powers the Assessments
     * summary/nav badge with a single grouped query instead of materializing every
     * row. Each element is {@code [status, count]}.
     */
    @Query("""
            SELECT a.status, count(a) FROM Assessment a
            WHERE a.deletedAt IS NULL
            GROUP BY a.status
            """)
    List<Object[]> countByStatusGroupedAll();

    /** Org-scoped variant: grouped status counts restricted to a single organization. */
    @Query("""
            SELECT a.status, count(a) FROM Assessment a
            WHERE a.deletedAt IS NULL
              AND a.organizationId = :orgId
            GROUP BY a.status
            """)
    List<Object[]> countByStatusGrouped(String orgId);

    /** Owned-scope variant: grouped status counts restricted to the given application ids. */
    @Query("""
            SELECT a.status, count(a) FROM Assessment a
            WHERE a.deletedAt IS NULL
              AND a.applicationId IN :applicationIds
            GROUP BY a.status
            """)
    List<Object[]> countByStatusGroupedOwned(Collection<String> applicationIds);

    /** Same aggregate, restricted to the caller's teams (the {@code assessments:read:team} tier). */
    @Query("""
            SELECT a.status, count(a) FROM Assessment a
            WHERE a.deletedAt IS NULL
              AND a.teamId IN :teamIds
            GROUP BY a.status
            """)
    List<Object[]> countByStatusGroupedTeam(Collection<String> teamIds);

    /**
     * Same aggregate, restricted to assessments the caller is an assessor on (the
     * {@code assessments:read:assigned} tier). Matches the legacy single {@code assessorId}
     * as well as the {@code assessorIds} list, mirroring the list query.
     */
    @Query(value = """
            SELECT a.status, count(*) FROM assessments a
            WHERE a.deleted_at IS NULL
              AND (a.assessor_id = :assessorId
                   OR a.assessor_ids @> CAST(CONCAT('["', :assessorId, '"]') AS jsonb))
            GROUP BY a.status
            """, nativeQuery = true)
    List<Object[]> countByStatusGroupedAssigned(String assessorId);

    /**
     * Find all completed assessments (completedDate set) that have not yet had a successor
     * auto-scheduled. Date eligibility is checked per-record in the scheduler service
     * because the window depends on each application's frequency setting.
     */
    @Query("SELECT a FROM Assessment a WHERE a.completedDate IS NOT NULL AND a.autoScheduledSuccessorId IS NULL AND a.deletedAt IS NULL")
    List<Assessment> findCompletedWithNoSuccessor();
}
