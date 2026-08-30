package com.faction.clientportal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The body of a 402, carrying enough for the frontend to name what was blocked.
 *
 * <p>Separate from {@link ErrorResponse} so the extra fields do not appear as nulls on
 * every other error in the API. {@code code} is what client code should branch on;
 * {@code message} is for humans and may change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpgradeRequiredResponse {

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    @Builder.Default
    private int status = 402;

    @Builder.Default
    private String error = "Payment Required";

    /** {@code FEATURE_NOT_LICENSED} or {@code QUOTA_EXCEEDED}. */
    private String code;

    /** Feature key, when a whole capability was blocked. Null for a quota. */
    private String feature;

    /** Quota key, when a cap was reached. Null for a feature. */
    private String quota;

    /** The cap that was reached. Null for a feature. */
    private Integer limit;

    private String message;

    private String path;

    private String upgradeUrl;
}
