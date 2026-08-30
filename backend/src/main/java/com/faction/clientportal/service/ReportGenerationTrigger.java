package com.faction.clientportal.service;

import com.faction.clientportal.model.ReportDocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper that runs {@link ReportGenerationService} methods on a
 * background thread so the HTTP response can return immediately with 202.
 *
 * <p>Kept separate from the service implementation so that the
 * {@code @Async} proxy is always honoured (self-invocation is not proxied).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportGenerationTrigger {

    private final ReportGenerationService reportGenerationService;
    private final ReportDocumentService   reportDocumentService;

    @Async("reportGenerationExecutor")
    public void trigger(String assessmentId, String userId) {
        try {
            reportGenerationService.generateReport(assessmentId, userId);
        } catch (Exception e) {
            log.error("Async report generation failed for assessment {}: {}",
                    assessmentId, e.getMessage(), e);
            reportDocumentService.failStuckDocuments(assessmentId,
                    "Report generation failed: " + e.getMessage());
        }
    }

    @Async("reportGenerationExecutor")
    public void triggerUpload(String assessmentId, String userId, byte[] fileBytes,
                               ReportDocumentType uploadedType) {
        try {
            reportGenerationService.uploadReport(assessmentId, fileBytes, uploadedType, userId);
        } catch (Exception e) {
            log.error("Async report upload processing failed for assessment {}: {}",
                    assessmentId, e.getMessage(), e);
            reportDocumentService.failStuckDocuments(assessmentId,
                    "Report upload processing failed: " + e.getMessage());
        }
    }
}
