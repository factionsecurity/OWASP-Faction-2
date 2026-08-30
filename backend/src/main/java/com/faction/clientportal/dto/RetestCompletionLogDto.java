package com.faction.clientportal.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One line of the retest activity log: a retest that was verified, what the verdict was, who
 * signed off, and which finding it was against.
 *
 * <p>Read from the retests themselves rather than a separate log table — a completed retest
 * already <em>is</em> the record of the event, and duplicating it into a second store would
 * give two versions of the same fact that can disagree.
 */
@Data
@Builder
public class RetestCompletionLogDto {

    private String retestId;

    /** PASSED or FAILED — the retest's terminal status. */
    private String status;

    /** PASS or FAIL, as recorded by whoever verified it. */
    private String result;

    /** When it was verified. */
    private LocalDateTime completedAt;

    /** Who verified it, stamped at completion rather than derived from the last edit. */
    private String completedBy;
    private String completedByName;

    private String vulnerabilityId;
    private String vulnerabilityName;
    private String severity;

    private String assessmentId;
    private String assessmentName;
    private String applicationId;
    private String applicationName;
    private String organizationId;
    private String organizationName;

    /** The verifier's note, if they left one. */
    private String comment;
}
