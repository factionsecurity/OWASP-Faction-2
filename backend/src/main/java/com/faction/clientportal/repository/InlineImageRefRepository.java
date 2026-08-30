package com.faction.clientportal.repository;

import com.faction.clientportal.model.InlineImageRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InlineImageRefRepository extends JpaRepository<InlineImageRef, String> {

    long countByImageId(String imageId);

    List<InlineImageRef> findByAssessmentIdAndFieldId(String assessmentId, String fieldId);

    void deleteByAssessmentId(String assessmentId);

    void deleteByAssessmentIdAndFieldId(String assessmentId, String fieldId);
}
