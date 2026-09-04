package com.faction.clientportal.repository;

import com.faction.clientportal.model.InlineImageRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InlineImageRefRepository extends JpaRepository<InlineImageRef, String> {

    long countByImageId(String imageId);

    List<InlineImageRef> findByAssessmentIdAndFieldId(String assessmentId, String fieldId);

    /**
     * Every reference from one field, whichever assessment each image belongs to.
     *
     * <p>Needed by surfaces that are not scoped to a single assessment — a notebook node is
     * anchored to an application and can quote screenshots uploaded against several assessments,
     * so its references cannot be reconciled by (assessment, field) the way an assessment's own
     * fields are.
     */
    List<InlineImageRef> findByFieldId(String fieldId);

    void deleteByAssessmentId(String assessmentId);

    void deleteByAssessmentIdAndFieldId(String assessmentId, String fieldId);
}
