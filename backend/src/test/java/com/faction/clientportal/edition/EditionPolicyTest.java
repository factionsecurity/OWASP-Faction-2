package com.faction.clientportal.edition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The caps and gates the open source edition ships with.
 *
 * <p>These numbers are the product promise, so they are asserted literally rather than
 * read back from the enum — a test that reuses the constant it is checking would pass
 * just as happily if someone changed the limit by accident.
 */
class EditionPolicyTest {

    private final EditionPolicy community = new CommunityEditionPolicy();

    @Test
    void communityCapsMatchTheAdvertisedLimits() {
        assertThat(community.edition()).isEqualTo(Edition.COMMUNITY);
        assertThat(community.limit(Quota.AI_PROVIDERS)).isEqualTo(1);
        assertThat(community.limit(Quota.AI_PROMPTS)).isEqualTo(4);
        assertThat(community.limit(Quota.EXTENSIONS)).isEqualTo(2);
    }

    @Test
    void communityDisablesEveryPaidFeature() {
        for (Feature feature : Feature.values()) {
            assertThat(community.enabled(feature))
                    .as("%s must be off in the open source edition", feature)
                    .isFalse();
        }
    }

    @Test
    void requireThrowsForAnUnlicensedFeatureAndNamesIt() {
        assertThatThrownBy(() -> community.require(Feature.SSO))
                .isInstanceOf(FeatureNotLicensedException.class)
                .satisfies(ex -> assertThat(((FeatureNotLicensedException) ex).getFeature())
                        .isEqualTo(Feature.SSO));

    }

    /**
     * The boundary is the whole point: with three prompts saved a fourth is allowed, with
     * four it is not. Off by one here either gives away a slot or refuses one that was
     * promised.
     */
    @Test
    void requireHeadroomAllowsUpToTheLimitAndRefusesAtIt() {
        assertThatCode(() -> community.requireHeadroom(Quota.AI_PROMPTS, 0)).doesNotThrowAnyException();
        assertThatCode(() -> community.requireHeadroom(Quota.AI_PROMPTS, 3)).doesNotThrowAnyException();

        assertThatThrownBy(() -> community.requireHeadroom(Quota.AI_PROMPTS, 4))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(ex -> {
                    QuotaExceededException q = (QuotaExceededException) ex;
                    assertThat(q.getQuota()).isEqualTo(Quota.AI_PROMPTS);
                    assertThat(q.getLimit()).isEqualTo(4);
                });
    }

    /** Seats are uncapped in the open source edition — there is no USERS quota at all. */
    @Test
    void thereIsNoSeatQuota() {
        assertThat(java.util.Arrays.stream(Quota.values()).map(Quota::getKey))
                .doesNotContain("users");
    }

    /** A count already past the cap — drifted data, or a race — must still be refused. */
    @Test
    void requireHeadroomRefusesWhenAlreadyOverTheLimit() {
        assertThatThrownBy(() -> community.requireHeadroom(Quota.EXTENSIONS, 9))
                .isInstanceOf(QuotaExceededException.class);
    }

    /** Keys cross the wire and appear in 402 bodies, so they are contract, not cosmetics. */
    @Test
    void featureAndQuotaKeysAreStableAndUnique() {
        assertThat(java.util.Arrays.stream(Feature.values()).map(Feature::getKey))
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key).matches("[a-z0-9_]+"));
        assertThat(java.util.Arrays.stream(Quota.values()).map(Quota::getKey))
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key).matches("[a-z0-9_]+"));
    }
}
