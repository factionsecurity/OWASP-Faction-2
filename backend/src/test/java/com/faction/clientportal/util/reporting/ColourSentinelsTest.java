package com.faction.clientportal.util.reporting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hexes a template author paints to say "colour this from the data".
 *
 * <p>Each colourable dimension owns a <em>slot</em>, and each slot has two sentinels that mean the
 * same thing: a light one and a dark one. That pairing is the whole point. With a single hex per
 * dimension an author colouring a severity chip has to paint the cell and its text the same value,
 * and Word then shows amber on amber — the report comes out right but the template is unreadable
 * while you build it.
 *
 * <p>Which of the pair is painted where carries no meaning; the attribute position already says
 * whether it is a fill or a font colour. Both members resolve identically so there is no wrong way
 * round to get caught by.
 */
class ColourSentinelsTest {

    @Test
    void theBuiltInDimensionsOwnTheFirstThreeSlots() {
        assertThat(ColourSentinels.SLOT_SEVERITY).isEqualTo(1);
        assertThat(ColourSentinels.SLOT_LIKELIHOOD).isEqualTo(2);
        assertThat(ColourSentinels.SLOT_IMPACT).isEqualTo(3);
    }

    @Test
    void aSlotsLightSentinelIsTheFacPrefixPlusItsNumber() {
        assertThat(ColourSentinels.light(1)).isEqualTo("FAC701");
        assertThat(ColourSentinels.light(2)).isEqualTo("FAC702");
        assertThat(ColourSentinels.light(3)).isEqualTo("FAC703");
    }

    @Test
    void aSlotsDarkSentinelSharesItsNumber() {
        assertThat(ColourSentinels.dark(1)).isEqualTo("1A0701");
        assertThat(ColourSentinels.dark(2)).isEqualTo("1A0702");
        assertThat(ColourSentinels.dark(3)).isEqualTo("1A0703");
    }

    /** Slot 10 must be 0A, not 10 — the trailing byte is hex, so a template author reads it as hex. */
    @Test
    void slotsAboveNineAreHexNotDecimal() {
        assertThat(ColourSentinels.light(10)).isEqualTo("FAC70A");
        assertThat(ColourSentinels.dark(10)).isEqualTo("1A070A");
        assertThat(ColourSentinels.light(255)).isEqualTo("FAC7FF");
    }

    @Test
    void bothSentinelsOfAPairResolveToTheSameSlot() {
        assertThat(ColourSentinels.slotOf("FAC701")).isEqualTo(1);
        assertThat(ColourSentinels.slotOf("1A0701")).isEqualTo(1);
        assertThat(ColourSentinels.slotOf("FAC70A")).isEqualTo(10);
        assertThat(ColourSentinels.slotOf("1A07FF")).isEqualTo(255);
    }

    /**
     * Word hands back whatever case the file holds, and an author pasting a hex may type either.
     * A sentinel that only matched uppercase would fail silently — the cell just stays that colour.
     */
    @Test
    void aSentinelIsRecognisedWhateverItsCase() {
        assertThat(ColourSentinels.slotOf("fac701")).isEqualTo(1);
        assertThat(ColourSentinels.slotOf("1a0701")).isEqualTo(1);
    }

    @Test
    void anOrdinaryColourIsNotASentinel() {
        assertThat(ColourSentinels.slotOf("FF0000")).isNull();
        assertThat(ColourSentinels.slotOf("000000")).isNull();
        assertThat(ColourSentinels.slotOf("FAC800")).isNull();
        assertThat(ColourSentinels.slotOf("1A0801")).isNull();
    }

    /**
     * Slot 0 is not allocated to anything, so {@code FAC700} is an ordinary colour. Leaving it
     * unclaimed keeps the first real slot at 01, which is what the existing templates already paint.
     */
    @Test
    void slotZeroIsNotASentinel() {
        assertThat(ColourSentinels.slotOf("FAC700")).isNull();
        assertThat(ColourSentinels.slotOf("1A0700")).isNull();
    }

    @Test
    void rubbishIsNotASentinel() {
        assertThat(ColourSentinels.slotOf(null)).isNull();
        assertThat(ColourSentinels.slotOf("")).isNull();
        assertThat(ColourSentinels.slotOf("FAC7")).isNull();
        assertThat(ColourSentinels.slotOf("FAC7ZZ")).isNull();
        assertThat(ColourSentinels.slotOf("auto")).isNull();
    }

    /** Custom fields start after the three built-ins. */
    @Test
    void theFirstSlotAvailableToACustomFieldIsFour() {
        assertThat(ColourSentinels.FIRST_CUSTOM_SLOT).isEqualTo(4);
    }

    @Test
    void slotsOutsideTheUsableRangeAreRejected() {
        assertThat(ColourSentinels.isUsableSlot(0)).isFalse();
        assertThat(ColourSentinels.isUsableSlot(1)).isTrue();
        assertThat(ColourSentinels.isUsableSlot(255)).isTrue();
        assertThat(ColourSentinels.isUsableSlot(256)).isFalse();
        assertThat(ColourSentinels.isUsableSlot(-1)).isFalse();
    }

    /**
     * A pinning test, not a behaviour test — these values are already correct, and the point is
     * that they stay that way.
     *
     * <p>The Report Designer computes the same hexes in TypeScript, in
     * {@code frontend/src/components/FindingColours.tsx} ({@code lightSentinel}/{@code darkSentinel}),
     * to print the legend an author copies from. Nothing links the two. If a prefix or the slot
     * format changes here and not there, the legend keeps printing hexes that no longer resolve —
     * which looks to the author like painting simply does not work.
     *
     * <p>So: change these and this test fails, naming the file to change with it.
     */
    @Test
    void theHexesTheReportDesignerLegendPrintsAreExactlyThese() {
        assertThat(ColourSentinels.light(ColourSentinels.SLOT_SEVERITY)).isEqualTo("FAC701");
        assertThat(ColourSentinels.dark(ColourSentinels.SLOT_SEVERITY)).isEqualTo("1A0701");
        assertThat(ColourSentinels.light(ColourSentinels.SLOT_LIKELIHOOD)).isEqualTo("FAC702");
        assertThat(ColourSentinels.dark(ColourSentinels.SLOT_LIKELIHOOD)).isEqualTo("1A0702");
        assertThat(ColourSentinels.light(ColourSentinels.SLOT_IMPACT)).isEqualTo("FAC703");
        assertThat(ColourSentinels.dark(ColourSentinels.SLOT_IMPACT)).isEqualTo("1A0703");
        // The first slot the designer allocates to a user-defined field.
        assertThat(ColourSentinels.light(ColourSentinels.FIRST_CUSTOM_SLOT)).isEqualTo("FAC704");
        assertThat(ColourSentinels.dark(ColourSentinels.FIRST_CUSTOM_SLOT)).isEqualTo("1A0704");
    }

    /**
     * The light member must stay light and the dark member dark, or the pair stops solving the
     * problem it exists for: an author painting dark-on-light needs the two to actually contrast
     * in Word, before any of them resolve.
     */
    @Test
    void theLightMemberIsLightAndTheDarkMemberIsDark() {
        for (int slot : new int[] { 1, 2, 3, ColourSentinels.FIRST_CUSTOM_SLOT }) {
            assertThat(luminance(ColourSentinels.light(slot)))
                    .as("light sentinel for slot " + slot)
                    .isGreaterThan(0.5);
            assertThat(luminance(ColourSentinels.dark(slot)))
                    .as("dark sentinel for slot " + slot)
                    .isLessThan(0.2);
        }
    }

    /** Rough perceived brightness, 0–1. Enough to tell "amber" from "nearly black". */
    private static double luminance(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }
}
