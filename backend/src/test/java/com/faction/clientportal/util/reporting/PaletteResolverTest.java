package com.faction.clientportal.util.reporting;

import com.faction.clientportal.model.ReportPalette;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a painted sentinel into the colour one finding should actually be rendered in.
 *
 * <p>The fallbacks matter as much as the lookups. An author can paint a sentinel for a dimension
 * nobody has configured, or a finding can hold a likelihood value the palette has never seen. In
 * both cases the sentinel must resolve to something ordinary — black text, white fill — because
 * the alternative is that the reserved amber survives into a report delivered to a client.
 */
class PaletteResolverTest {

    private static final String BLACK = "000000";
    private static final String WHITE = "FFFFFF";

    private ReportData.ReportVulnerability finding(String severityKey, String likelihood,
                                                   String impact) {
        return ReportData.ReportVulnerability.builder()
                .name("SQL Injection")
                .severity("Sev-1")          // deliberately renamed: nothing may key on this
                .severityKey(severityKey)
                .likelihood(likelihood)
                .impact(impact)
                .build();
    }

    private ReportPalette paletteWithSeverity() {
        ReportPalette palette = ReportPalette.defaults();
        // Explicit, so the lookup tests do not lean on whatever the seeded defaults are.
        palette.putSeverity("CRITICAL", ReportPalette.ColourPair.of("B91C1C", "FCE1E1"));
        palette.putSeverity("HIGH", ReportPalette.ColourPair.of("C2410C", "FEE9DA"));
        palette.putLikelihood("High", ReportPalette.ColourPair.of("AA0000", "FFDDDD"));
        palette.putImpact("Low", ReportPalette.ColourPair.of("0000AA", "DDDDFF"));
        return palette;
    }

    @Test
    void severityResolvesFromTheEnumKey() {
        PaletteResolver resolver = new PaletteResolver(paletteWithSeverity());
        var vuln = finding("CRITICAL", "High", "Low");

        assertThat(resolver.text(ColourSentinels.SLOT_SEVERITY, vuln)).isEqualTo("B91C1C");
        assertThat(resolver.fill(ColourSentinels.SLOT_SEVERITY, vuln)).isEqualTo("FCE1E1");
    }

    /**
     * The regression test for the bug this replaces. The finding's displayed severity is "Sev-1";
     * under the old label-keyed palette that matched nothing and rendered black.
     */
    @Test
    void aRenamedSeverityStillGetsItsColour() {
        PaletteResolver resolver = new PaletteResolver(paletteWithSeverity());

        assertThat(resolver.text(ColourSentinels.SLOT_SEVERITY, finding("HIGH", null, null)))
                .isEqualTo("C2410C");
    }

    @Test
    void likelihoodAndImpactResolveFromTheirStoredValues() {
        PaletteResolver resolver = new PaletteResolver(paletteWithSeverity());
        var vuln = finding("CRITICAL", "High", "Low");

        assertThat(resolver.text(ColourSentinels.SLOT_LIKELIHOOD, vuln)).isEqualTo("AA0000");
        assertThat(resolver.fill(ColourSentinels.SLOT_LIKELIHOOD, vuln)).isEqualTo("FFDDDD");
        assertThat(resolver.text(ColourSentinels.SLOT_IMPACT, vuln)).isEqualTo("0000AA");
        assertThat(resolver.fill(ColourSentinels.SLOT_IMPACT, vuln)).isEqualTo("DDDDFF");
    }

    @Test
    void aCustomFieldResolvesFromItsOwnValue() {
        ReportPalette palette = ReportPalette.defaults();
        int slot = palette.allocateSlot("risk_rating");
        palette.getCustomFields().get("risk_rating")
                .putValue("Elevated", ReportPalette.ColourPair.of("7C2D12", "FFEDD5"));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("risk_rating", "Elevated");
        var vuln = ReportData.ReportVulnerability.builder().fieldValues(values).build();

        PaletteResolver resolver = new PaletteResolver(palette);
        assertThat(resolver.text(slot, vuln)).isEqualTo("7C2D12");
        assertThat(resolver.fill(slot, vuln)).isEqualTo("FFEDD5");
    }

    // ── fallbacks ────────────────────────────────────────────────────────────

    @Test
    void aValueTheePaletteDoesNotKnowFallsBackToBlackOnWhite() {
        PaletteResolver resolver = new PaletteResolver(paletteWithSeverity());
        var vuln = finding("CRITICAL", "Improbable", "Catastrophic");

        assertThat(resolver.text(ColourSentinels.SLOT_LIKELIHOOD, vuln)).isEqualTo(BLACK);
        assertThat(resolver.fill(ColourSentinels.SLOT_LIKELIHOOD, vuln)).isEqualTo(WHITE);
    }

    @Test
    void aFindingWithNoSeverityFallsBack() {
        PaletteResolver resolver = new PaletteResolver(paletteWithSeverity());

        assertThat(resolver.text(ColourSentinels.SLOT_SEVERITY, finding(null, null, null)))
                .isEqualTo(BLACK);
        assertThat(resolver.fill(ColourSentinels.SLOT_SEVERITY, finding("", null, null)))
                .isEqualTo(WHITE);
    }

    /**
     * An author can paint any slot they like. One nobody allocated must still be neutralised, or
     * the reserved amber ships to the client.
     */
    @Test
    void anUnallocatedSlotFallsBack() {
        PaletteResolver resolver = new PaletteResolver(paletteWithSeverity());
        var vuln = finding("CRITICAL", "High", "Low");

        assertThat(resolver.text(99, vuln)).isEqualTo(BLACK);
        assertThat(resolver.fill(99, vuln)).isEqualTo(WHITE);
    }

    @Test
    void aTemplateWithNoPaletteAtAllStillResolvesEverySlot() {
        PaletteResolver resolver = new PaletteResolver(null);
        var vuln = finding("CRITICAL", "High", "Low");

        assertThat(resolver.text(ColourSentinels.SLOT_SEVERITY, vuln)).isEqualTo(BLACK);
        assertThat(resolver.fill(ColourSentinels.SLOT_SEVERITY, vuln)).isEqualTo(WHITE);
    }

    @Test
    void aHalfConfiguredPairFallsBackOnlyForTheMissingHalf() {
        ReportPalette palette = ReportPalette.defaults();
        palette.putLikelihood("High", ReportPalette.ColourPair.of("AA0000", null));

        PaletteResolver resolver = new PaletteResolver(palette);
        var vuln = finding("CRITICAL", "High", null);

        assertThat(resolver.text(ColourSentinels.SLOT_LIKELIHOOD, vuln)).isEqualTo("AA0000");
        assertThat(resolver.fill(ColourSentinels.SLOT_LIKELIHOOD, vuln)).isEqualTo(WHITE);
    }

    /**
     * Word is case-insensitive about hex but the XML is not rewritten by hand, so whatever the
     * palette holds goes straight into the document. Normalising to upper case keeps the output
     * consistent regardless of what a colour picker submitted.
     */
    @Test
    void aColourIsNormalisedToUpperCaseWithoutItsHash() {
        ReportPalette palette = ReportPalette.defaults();
        palette.putImpact("High", ReportPalette.ColourPair.of("#ab12cd", "#EF34ab"));

        PaletteResolver resolver = new PaletteResolver(palette);
        var vuln = finding("CRITICAL", null, "High");

        assertThat(resolver.text(ColourSentinels.SLOT_IMPACT, vuln)).isEqualTo("AB12CD");
        assertThat(resolver.fill(ColourSentinels.SLOT_IMPACT, vuln)).isEqualTo("EF34AB");
    }

    // ── likelihood and impact are the severity levels, stored as free text ───

    /**
     * Likelihood and impact are edited with the same picker as severity — the same five levels —
     * but stored as plain strings, and the same field has been written both ways: the finding
     * screens store "CRITICAL" while the default-vulnerability form stored "Critical". Matching
     * only one spelling would colour roughly half the data and leave the rest black, which is the
     * kind of bug nobody notices until a client asks why one table is monochrome.
     */
    @Test
    void likelihoodMatchesWhicheverCaseTheValueWasStoredIn() {
        ReportPalette palette = ReportPalette.defaults();
        palette.putLikelihood("CRITICAL", ReportPalette.ColourPair.of("AA0000", "FFDDDD"));
        PaletteResolver resolver = new PaletteResolver(palette);

        assertThat(resolver.text(ColourSentinels.SLOT_LIKELIHOOD, finding("CRITICAL", "Critical", null)))
                .isEqualTo("AA0000");
        assertThat(resolver.text(ColourSentinels.SLOT_LIKELIHOOD, finding("CRITICAL", "critical", null)))
                .isEqualTo("AA0000");
        assertThat(resolver.text(ColourSentinels.SLOT_LIKELIHOOD, finding("CRITICAL", "CRITICAL", null)))
                .isEqualTo("AA0000");
    }

    @Test
    void impactMatchesWhicheverCaseTheValueWasStoredIn() {
        ReportPalette palette = ReportPalette.defaults();
        palette.putImpact("HIGH", ReportPalette.ColourPair.of("0000AA", "DDDDFF"));

        assertThat(new PaletteResolver(palette)
                .fill(ColourSentinels.SLOT_IMPACT, finding("CRITICAL", null, "High")))
                .isEqualTo("DDDDFF");
    }

    /**
     * A palette whose keys were saved in mixed case still matches an uppercase stored value.
     *
     * <p>Built from an empty map rather than {@code defaults()}, which seeds likelihood with
     * {@code MEDIUM} already: a map holding both spellings is a different question, answered below.
     */
    @Test
    void aMixedCasePaletteKeyStillMatches() {
        ReportPalette palette = ReportPalette.builder().build();
        palette.putLikelihood("Medium", ReportPalette.ColourPair.of("BB7700", "FFEECC"));

        assertThat(new PaletteResolver(palette)
                .text(ColourSentinels.SLOT_LIKELIHOOD, finding("CRITICAL", "MEDIUM", null)))
                .isEqualTo("BB7700");
    }

    /**
     * When a palette somehow holds both spellings — the seeded {@code MEDIUM} plus a {@code Medium}
     * someone's import added — the exact match wins. Any other rule would make which colour you get
     * depend on map iteration order.
     */
    @Test
    void anExactKeyBeatsACaseInsensitiveOne() {
        ReportPalette palette = ReportPalette.builder().build();
        palette.putLikelihood("Medium", ReportPalette.ColourPair.of("BB7700", "FFEECC"));
        palette.putLikelihood("MEDIUM", ReportPalette.ColourPair.of("112233", "445566"));

        assertThat(new PaletteResolver(palette)
                .text(ColourSentinels.SLOT_LIKELIHOOD, finding("CRITICAL", "MEDIUM", null)))
                .isEqualTo("112233");
    }

    /** A custom field's values are the author's own words, so those match exactly. */
    @Test
    void aCustomFieldValueIsMatchedCaseInsensitivelyToo() {
        ReportPalette palette = ReportPalette.defaults();
        int slot = palette.allocateSlot("risk_rating");
        palette.getCustomFields().get("risk_rating")
                .putValue("Elevated", ReportPalette.ColourPair.of("7C2D12", "FFEDD5"));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("risk_rating", "elevated");
        var vuln = ReportData.ReportVulnerability.builder().fieldValues(values).build();

        assertThat(new PaletteResolver(palette).text(slot, vuln)).isEqualTo("7C2D12");
    }
}
