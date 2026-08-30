package com.faction.clientportal.edition;

/**
 * The single seam between the open source edition and the paid overlay.
 *
 * <p>Every gate in the application resolves to a call on this interface, so that
 * "is this allowed?" is asked in exactly one vocabulary and answered in exactly one
 * place. Callers never branch on {@link Edition} directly.
 *
 * <h2>How an edition is chosen</h2>
 *
 * There is no licence key and no runtime switch in production. {@link CommunityEditionPolicy}
 * is the only implementation in the open source build; the overlay contributes a
 * {@code @Primary} bean that supersedes it simply by being on the classpath. Nothing to
 * forge, and no branch to patch out, because the paid code is not in the open source
 * artifact at all.
 *
 * <p>The one exception is development: the enterprise build honours
 * {@code faction.edition=community}, which suppresses the overlay bean so the open source
 * experience can be exercised without a rebuild. That override cannot unlock anything —
 * it only ever takes capability away.
 */
public interface EditionPolicy {

    /** Which edition this build reports as. */
    Edition edition();

    /** Whether a paid capability is available. */
    boolean enabled(Feature feature);

    /** The cap for a countable resource; {@link Integer#MAX_VALUE} means unlimited. */
    int limit(Quota quota);

    /**
     * Asserts a paid capability is available.
     *
     * @throws FeatureNotLicensedException when it is not
     */
    default void require(Feature feature) {
        if (!enabled(feature)) {
            throw new FeatureNotLicensedException(feature);
        }
    }

    /**
     * Asserts there is room for one more of a capped resource.
     *
     * <p>Call this <em>before</em> the write, passing the count as it stands now. A
     * current count equal to the limit means the next one would exceed it.
     *
     * @param current how many already exist
     * @throws QuotaExceededException when the limit has been reached
     */
    default void requireHeadroom(Quota quota, long current) {
        int max = limit(quota);
        if (max != Integer.MAX_VALUE && current >= max) {
            throw new QuotaExceededException(quota, max);
        }
    }

    /** Whether a cap applies at all, for rendering "3 of 4" against "3". */
    default boolean isLimited(Quota quota) {
        return limit(quota) != Integer.MAX_VALUE;
    }
}
