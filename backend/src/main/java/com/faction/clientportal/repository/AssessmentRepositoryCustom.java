package com.faction.clientportal.repository;

import com.faction.clientportal.model.Assessment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Custom advanced assessment search that filters, sorts, and paginates in the database
 * (one page query + one count) instead of materializing every row and filtering in memory.
 */
public interface AssessmentRepositoryCustom {

    /**
     * Return one page of non-deleted assessments matching {@code criteria}, ordered per
     * {@code pageable}'s sort (whitelisted columns; unknown → createdAt). An unpaged
     * {@code pageable} returns all matches (e.g. CSV export).
     */
    Page<Assessment> searchAdvanced(AssessmentSearchCriteria criteria, Pageable pageable);
}
