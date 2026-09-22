package com.faction.clientportal.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The colours a report template paints its findings with.
 *
 * <p>This used to live inside the {@code .docx} as {@code ${color Critical=C00000,…}} marker
 * paragraphs. Two things were wrong with that: the keys were the <em>display</em> labels, so
 * renaming a severity in the terminology settings silently returned every finding to black; and
 * the only way to change a colour was to open Word.
 *
 * <p>Severity is therefore keyed on the enum constant, which no rename touches. Likelihood and
 * impact cannot be — they are free-text on the vulnerability with no closed vocabulary — so they
 * stay keyed on the stored value. That asymmetry is deliberate.
 */
class ReportPaletteTest {

    @Test
    void aFreshPaletteHasAColourForEverySeverity() {
        ReportPalette palette = ReportPalette.defaults();

        for (VulnerabilitySeverity severity : VulnerabilitySeverity.values()) {
            assertThat(palette.getSeverity()).containsKey(severity.name());
        }
    }

    /**
     * The defaults are the web UI's own severity palette, so a report and the screen it came from
     * do not disagree about what Critical looks like. Light-mode values, because a report is
     * printed on white.
     */
    @Test
    void theSeverityDefaultsMatchTheWebUiPalette() {
        ReportPalette palette = ReportPalette.defaults();

        assertThat(palette.getSeverity().get("CRITICAL"))
                .returns("B91C1C", ReportPalette.ColourPair::getText)
                .returns("FCE1E1", ReportPalette.ColourPair::getFill);
        assertThat(palette.getSeverity().get("INFORMATIONAL"))
                .returns("334155", ReportPalette.ColourPair::getText)
                .returns("EFF0F2", ReportPalette.ColourPair::getFill);
    }

    @Test
    void severityIsKeyedOnTheEnumNameNotTheDisplayLabel() {
        assertThat(ReportPalette.defaults().getSeverity())
                .containsKey("CRITICAL")
                .doesNotContainKey("Critical");
    }

    /**
     * Likelihood and impact are the same five levels as severity — all three are edited with
     * {@code SeverityLevelSelect} — so all three seed together. Leaving the other two empty meant a
     * template painting a likelihood sentinel rendered black on white until someone went looking
     * for a setting they had no reason to know existed.
     */
    @Test
    void allThreeRatingDimensionsSeedWithTheSameColours() {
        ReportPalette palette = ReportPalette.defaults();

        assertThat(palette.getLikelihood()).isEqualTo(palette.getSeverity());
        assertThat(palette.getImpact()).isEqualTo(palette.getSeverity());
    }

    @Test
    void aFreshPaletteHasNoCustomFieldColours() {
        assertThat(ReportPalette.defaults().getCustomFields()).isEmpty();
    }

    /**
     * The three dimensions share one set of colours until someone says otherwise, which is what the
     * designer shows by default. Stored rather than derived from whether the three maps happen to
     * be equal: a user who splits them and has not yet changed anything would otherwise see the
     * checkbox silently uncheck itself on reload.
     */
    @Test
    void theRatingDimensionsShareOneSetOfColoursByDefault() {
        assertThat(ReportPalette.defaults().getSeparateRatingColours()).isFalse();
    }

    @Test
    void copyingCarriesWhetherTheRatingsWereSplit() {
        ReportPalette palette = ReportPalette.defaults();
        palette.setSeparateRatingColours(true);
        palette.getImpact().put("LOW", ReportPalette.ColourPair.of("123456", "ABCDEF"));

        ReportPalette copy = palette.copy();

        assertThat(copy.getSeparateRatingColours()).isTrue();
        assertThat(copy.getImpact().get("LOW").getFill()).isEqualTo("ABCDEF");
    }

    /** Seeded likelihood and impact must be their own objects, not aliases of severity's. */
    @Test
    void theSeededRatingDimensionsDoNotShareObjects() {
        ReportPalette palette = ReportPalette.defaults();

        palette.getLikelihood().put("CRITICAL", ReportPalette.ColourPair.of("000000", "FFFFFF"));

        assertThat(palette.getSeverity().get("CRITICAL").getText()).isEqualTo("B91C1C");
        assertThat(palette.getImpact().get("CRITICAL").getText()).isEqualTo("B91C1C");
    }

    // ── slot allocation ──────────────────────────────────────────────────────

    @Test
    void theFirstCustomFieldGetsSlotFour() {
        ReportPalette palette = ReportPalette.defaults();

        assertThat(palette.allocateSlot("risk_rating")).isEqualTo(4);
        assertThat(palette.getCustomFields().get("risk_rating").getSlot()).isEqualTo(4);
    }

    @Test
    void eachNewCustomFieldGetsTheNextSlot() {
        ReportPalette palette = ReportPalette.defaults();

        assertThat(palette.allocateSlot("risk_rating")).isEqualTo(4);
        assertThat(palette.allocateSlot("data_class")).isEqualTo(5);
        assertThat(palette.allocateSlot("exposure")).isEqualTo(6);
    }

    @Test
    void allocatingAFieldThatAlreadyHasASlotReturnsTheSameOne() {
        ReportPalette palette = ReportPalette.defaults();
        int first = palette.allocateSlot("risk_rating");

        assertThat(palette.allocateSlot("risk_rating")).isEqualTo(first);
        assertThat(palette.getCustomFields()).hasSize(1);
    }

    /**
     * The sentinel for a retired slot may still be painted somewhere in a template body. Handing
     * that number to a different field would silently recolour whatever cell it is sitting in, so
     * the counter only ever goes up.
     */
    @Test
    void aReleasedSlotIsNeverHandedOutAgain() {
        ReportPalette palette = ReportPalette.defaults();
        palette.allocateSlot("risk_rating");   // 4
        palette.allocateSlot("data_class");    // 5

        palette.releaseSlot("data_class");

        assertThat(palette.getCustomFields()).doesNotContainKey("data_class");
        assertThat(palette.allocateSlot("exposure")).isEqualTo(6);
    }

    @Test
    void releasingTheOnlyFieldStillDoesNotRewindTheCounter() {
        ReportPalette palette = ReportPalette.defaults();
        palette.allocateSlot("risk_rating");
        palette.releaseSlot("risk_rating");

        assertThat(palette.allocateSlot("something_else")).isEqualTo(5);
    }

    @Test
    void releasingAFieldThatWasNeverAllocatedIsHarmless() {
        ReportPalette palette = ReportPalette.defaults();

        palette.releaseSlot("never_existed");

        assertThat(palette.allocateSlot("risk_rating")).isEqualTo(4);
    }

    // ── copying ──────────────────────────────────────────────────────────────

    /**
     * Cloning a report template must not leave the two sharing one palette, for the same reason
     * {@link UserDefinedField#copy()} rebuilds its dropdown options: editing one template's colours
     * would otherwise recolour the other's reports.
     */
    @Test
    void copyingSharesNothingMutable() {
        ReportPalette original = ReportPalette.defaults();
        original.allocateSlot("risk_rating");
        original.getCustomFields().get("risk_rating").getValues()
                .put("Elevated", ReportPalette.ColourPair.of("111111", "EEEEEE"));

        ReportPalette copy = original.copy();
        copy.getSeverity().put("CRITICAL", ReportPalette.ColourPair.of("000000", "FFFFFF"));
        copy.getCustomFields().get("risk_rating").getValues()
                .put("Elevated", ReportPalette.ColourPair.of("222222", "DDDDDD"));

        assertThat(original.getSeverity().get("CRITICAL").getText()).isEqualTo("B91C1C");
        assertThat(original.getCustomFields().get("risk_rating").getValues().get("Elevated").getText())
                .isEqualTo("111111");
    }

    @Test
    void aCopyKeepsTheSlotCounterSoTheCloneCannotReissueASlot() {
        ReportPalette original = ReportPalette.defaults();
        original.allocateSlot("risk_rating");
        original.allocateSlot("data_class");

        ReportPalette copy = original.copy();

        assertThat(copy.getCustomFields().get("data_class").getSlot()).isEqualTo(5);
        assertThat(copy.allocateSlot("exposure")).isEqualTo(6);
    }
}
