package com.faction.clientportal.model;

/**
 * The kinds of report artifacts: the assessment report's and the retest
 * report's.
 */
public enum ReportDocumentType {
    DOCX,
    PDF,
    ENCRYPTED_PDF,
    RETEST_DOCX,
    RETEST_PDF,
    RETEST_ENCRYPTED_PDF;

    /** The assessment report's artifacts. */
    public static final java.util.Set<ReportDocumentType> MAIN =
            java.util.Collections.unmodifiableSet(java.util.EnumSet.of(DOCX, PDF, ENCRYPTED_PDF));
    /** The retest report's artifacts, from the template's retest DOCX. */
    public static final java.util.Set<ReportDocumentType> RETEST =
            java.util.Collections.unmodifiableSet(java.util.EnumSet.of(RETEST_DOCX, RETEST_PDF, RETEST_ENCRYPTED_PDF));

    public boolean isRetest() {
        return RETEST.contains(this);
    }
}
