package com.faction.clientportal.util.reporting;

import com.faction.clientportal.model.ReportPalette;

import java.util.Map;

/**
 * Turns a painted colour slot into the colour one finding should be rendered in.
 *
 * <p>Every lookup resolves to something. An author can paint a sentinel for a dimension nobody has
 * configured, and a finding can hold a likelihood the palette has never seen; in both cases the
 * answer is an ordinary colour rather than nothing, because the alternative is that the reserved
 * amber survives into a report delivered to a client.
 */
class PaletteResolver {

    /** What an unconfigured or unknown value renders as — the same defaults the old code used. */
    private static final String FALLBACK_TEXT = "000000";
    private static final String FALLBACK_FILL = "FFFFFF";

    private final ReportPalette palette;

    PaletteResolver(ReportPalette palette) {
        this.palette = palette;
    }

    /** The font colour for this slot on this finding, as six upper-case hex digits. */
    String text(int slot, ReportData.ReportVulnerability vuln) {
        ReportPalette.ColourPair pair = pairFor(slot, vuln);
        return normalise(pair == null ? null : pair.getText(), FALLBACK_TEXT);
    }

    /** The cell fill for this slot on this finding, as six upper-case hex digits. */
    String fill(int slot, ReportData.ReportVulnerability vuln) {
        ReportPalette.ColourPair pair = pairFor(slot, vuln);
        return normalise(pair == null ? null : pair.getFill(), FALLBACK_FILL);
    }

    /**
     * The configured pair for this slot, or null when the dimension, the field or the finding's
     * value is unknown to the palette.
     */
    private ReportPalette.ColourPair pairFor(int slot, ReportData.ReportVulnerability vuln) {
        if (palette == null || vuln == null) return null;

        return switch (slot) {
            // Keyed on the enum name, never getSeverity(): that is the renameable label, and
            // keying on it is exactly the bug this palette replaces.
            case ColourSentinels.SLOT_SEVERITY   -> lookup(palette.getSeverity(), vuln.getSeverityKey());
            case ColourSentinels.SLOT_LIKELIHOOD -> lookup(palette.getLikelihood(), vuln.getLikelihood());
            case ColourSentinels.SLOT_IMPACT     -> lookup(palette.getImpact(), vuln.getImpact());
            default -> customField(slot, vuln);
        };
    }

    private ReportPalette.ColourPair customField(int slot, ReportData.ReportVulnerability vuln) {
        if (palette.getCustomFields() == null) return null;

        for (Map.Entry<String, ReportPalette.FieldColours> entry
                : palette.getCustomFields().entrySet()) {
            ReportPalette.FieldColours colours = entry.getValue();
            if (colours == null || colours.getSlot() == null || colours.getSlot() != slot) continue;
            return lookup(colours.getValues(), vuln.getFieldValue(entry.getKey()));
        }
        return null;
    }

    /**
     * Case-insensitive, and deliberately so. Likelihood and impact are edited with the same picker
     * as severity — the same five levels — but stored as plain strings, and the same field has been
     * written both ways over the product's life: the finding screens store {@code CRITICAL} while
     * the default-vulnerability form stored {@code Critical}. {@code canonicalSeverity} in the
     * frontend exists for the same reason. Matching one spelling only would colour part of the data
     * and leave the rest black.
     */
    private static ReportPalette.ColourPair lookup(Map<String, ReportPalette.ColourPair> colours,
                                                   String value) {
        if (colours == null || value == null || value.isEmpty()) return null;

        ReportPalette.ColourPair exact = colours.get(value);
        if (exact != null) return exact;

        for (Map.Entry<String, ReportPalette.ColourPair> entry : colours.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(value)) return entry.getValue();
        }
        return null;
    }

    /**
     * WordprocessingML wants six bare hex digits. A colour picker may submit a leading {@code #}
     * and either case, and the value goes straight into the document, so it is normalised here
     * rather than trusted.
     */
    private static String normalise(String colour, String fallback) {
        if (colour == null) return fallback;
        String hex = colour.startsWith("#") ? colour.substring(1) : colour;
        return hex.length() == 6 ? hex.toUpperCase() : fallback;
    }
}
