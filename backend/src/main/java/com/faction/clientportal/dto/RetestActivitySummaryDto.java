package com.faction.clientportal.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Pass/fail totals for a window of the retest activity log — "how many retests were completed
 * this week, and how many of them passed".
 *
 * <p>Counted in the database over the same window the log lists, not summed from the page on
 * screen, so the totals describe the period rather than the current page.
 */
@Data
@Builder
public class RetestActivitySummaryDto {

    private long passed;

    private long failed;

    /** Passed + failed. Cancelled retests are not completions and are counted nowhere here. */
    private long total;
}
