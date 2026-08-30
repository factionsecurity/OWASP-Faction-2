package com.faction.clientportal.edition;

import lombok.Getter;

/**
 * Thrown when a capped resource is already at its open source limit.
 *
 * <p>Mapped to HTTP 402 alongside {@link FeatureNotLicensedException} — the cause is the
 * same (this build, not this caller) and the frontend handles both with one prompt.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    private final transient Quota quota;
    private final int limit;

    public QuotaExceededException(Quota quota, int limit) {
        super("The open source edition is limited to " + limit + " "
                + quota.getDisplayName().toLowerCase() + ".");
        this.quota = quota;
        this.limit = limit;
    }
}
