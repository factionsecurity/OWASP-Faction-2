package com.faction.clientportal.repository;

import com.faction.clientportal.model.Retest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RetestRepository extends JpaRepository<Retest, String> {
    List<Retest> findByVulnerabilityIdAndDeletedAtIsNull(String vulnerabilityId);

    /** The still-open retests on a finding — used to refuse scheduling a second one alongside. */
    List<Retest> findByVulnerabilityIdAndStatusInAndDeletedAtIsNull(
            String vulnerabilityId, Collection<String> statuses);
    List<Retest> findByAssessmentIdAndDeletedAtIsNull(String assessmentId);
    Optional<Retest> findByIdAndDeletedAtIsNull(String id);
    @Query(value = "SELECT * FROM retests WHERE assigned_assessor_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) AND deleted_at IS NULL", nativeQuery = true)
    List<Retest> findByAssignedAssessorIdsContainingAndDeletedAtIsNull(String userId);
    List<Retest> findByDeletedAtIsNull();
    List<Retest> findByScheduledStartDateBetweenAndDeletedAtIsNull(LocalDateTime from, LocalDateTime to);

    /** Count of non-deleted retests in any of the given statuses — the retest half of the remediation queue badge. */
    long countByStatusInAndDeletedAtIsNull(Collection<String> statuses);

    /** Non-deleted retests for the given vulns in any of the given statuses — used to overlay each
     *  remediation-queue vuln row with its most recent PASSED/FAILED retest result. */
    List<Retest> findByVulnerabilityIdInAndStatusInAndDeletedAtIsNull(
            Collection<String> vulnerabilityIds, Collection<String> statuses);

    /** One page of retests verified in a window — the retest activity log. Ordered by the caller;
     *  {@code closedDate} is written only at completion, so it is the verification timestamp. */
    Page<Retest> findByStatusInAndClosedDateBetweenAndDeletedAtIsNull(
            Collection<String> statuses, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /** How many retests reached the given status in a window — the log's pass/fail totals. */
    long countByStatusAndClosedDateBetweenAndDeletedAtIsNull(
            String status, LocalDateTime from, LocalDateTime to);
}
