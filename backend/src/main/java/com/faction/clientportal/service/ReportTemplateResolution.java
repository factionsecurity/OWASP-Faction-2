package com.faction.clientportal.service;

import com.faction.clientportal.model.Assessment;
import com.faction.clientportal.model.ReportTemplate;
import com.faction.clientportal.repository.ReportTemplateRepository;

import java.util.Optional;

/**
 * Which report template a report is generated from.
 *
 * <p>The assessment's own template while it is live, so edits in the report designer show up in
 * the next report straight away. Once that template has been deleted or deactivated, the most
 * recently updated live template for the assessment's type takes over: otherwise every
 * assessment created from a retired template would be stuck on a template nobody can open,
 * which is how a retest DOCX uploaded to the current template went unseen. If the type has no
 * live template either, the retired one is still better than no report.
 */
final class ReportTemplateResolution {

    private ReportTemplateResolution() {}

    static Optional<ReportTemplate> forReport(ReportTemplateRepository repository, Assessment assessment) {
        Optional<ReportTemplate> own = assessment.getReportTemplateId() == null
                ? Optional.empty()
                : repository.findById(assessment.getReportTemplateId());
        if (own.isPresent() && isLive(own.get())) {
            return own;
        }
        if (assessment.getAssessmentTypeId() != null) {
            Optional<ReportTemplate> newest = repository
                    .findFirstByAssessmentTypeIdAndActiveTrueAndDeletedAtIsNullOrderByUpdatedAtDesc(
                            assessment.getAssessmentTypeId());
            if (newest.isPresent()) {
                return newest;
            }
        }
        return own;
    }

    /** A null active flag predates the column and counts as active, as it does everywhere else. */
    private static boolean isLive(ReportTemplate template) {
        return template.getDeletedAt() == null && !Boolean.FALSE.equals(template.getActive());
    }
}
