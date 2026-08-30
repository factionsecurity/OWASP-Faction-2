package com.faction.clientportal.repository;

import com.faction.clientportal.model.PeerReview;
import com.faction.clientportal.model.PeerReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PeerReviewRepository extends JpaRepository<PeerReview, String> {

    Optional<PeerReview> findByIdAndAssessmentId(String id, String assessmentId);

    List<PeerReview> findByAssessmentIdOrderByCreatedAtDesc(String assessmentId);

    Page<PeerReview> findByStatusIn(List<PeerReviewStatus> statuses, Pageable pageable);

    /** Unpaged variant — team-scoped callers must be filtered before paging. */
    List<PeerReview> findByStatusInOrderByCreatedAtDesc(List<PeerReviewStatus> statuses);
}
