package com.faction.clientportal.repository;

import com.faction.clientportal.model.ReportDocument;
import com.faction.clientportal.model.ReportDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportDocumentRepository extends JpaRepository<ReportDocument, String> {

    List<ReportDocument> findByAssessmentId(String assessmentId);

    Optional<ReportDocument> findByAssessmentIdAndDocType(String assessmentId, ReportDocumentType docType);
}
