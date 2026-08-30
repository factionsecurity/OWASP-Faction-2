package com.faction.clientportal.repository;

import com.faction.clientportal.model.InlineImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InlineImageRepository extends JpaRepository<InlineImage, String> {

    List<InlineImage> findByAssessmentId(String assessmentId);

    List<InlineImage> findByUploadedAtBefore(LocalDateTime threshold);
}
