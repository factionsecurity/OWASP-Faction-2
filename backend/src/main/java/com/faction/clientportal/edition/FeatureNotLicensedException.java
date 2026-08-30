package com.faction.clientportal.edition;

import lombok.Getter;

/**
 * Thrown when a paid capability is used in the open source edition.
 *
 * <p>Mapped to HTTP 402 with a machine-readable body, so the frontend can raise the
 * upgrade prompt rather than a generic error toast. Distinct from an authorization
 * failure on purpose: 403 means "not you", 402 means "not this build".
 */
@Getter
public class FeatureNotLicensedException extends RuntimeException {

    private final transient Feature feature;

    public FeatureNotLicensedException(Feature feature) {
        super(feature.getDisplayName() + " is not available in the open source edition.");
        this.feature = feature;
    }
}
