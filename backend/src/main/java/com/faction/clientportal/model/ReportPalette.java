package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The colours a report template paints its findings with, and the slot each colourable dimension
 * owns.
 *
 * <p>This used to live inside the {@code .docx} itself, as {@code ${color Critical=C00000,…}} and
 * {@code ${cells …}} marker paragraphs the author had to hide. Two things were wrong with that.
 * The keys were the <em>display</em> labels, so renaming a severity in the terminology settings
 * silently returned every finding to black — the same class of bug
 * {@link VulnerabilitySeverity} and {@code severityKey} already fixed for the count tokens. And
 * the only way to change a colour was to open Word.
 *
 * <p>Severity is therefore keyed on the enum constant, which no rename touches. Likelihood and
 * impact cannot be: they are free-text on {@link Vulnerability} with no closed vocabulary, so
 * there is nothing stable to key on and they stay keyed on the stored value. The asymmetry is
 * deliberate — closing those vocabularies is a much larger change.
 *
 * <p>Colours are stored the way WordprocessingML wants them: six hex digits, no {@code #}.
 *
 * <p>Persisted as a jsonb column, so this class stays plain data. Nothing here derives a value
 * from another field through a bean accessor; {@link #defaults()}, {@link #copy()} and the slot
 * methods are not getters and so are not serialised.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportPalette {

    /** Keyed on {@link VulnerabilitySeverity#name()} — never on the renameable label. */
    @Builder.Default
    private Map<String, ColourPair> severity = new LinkedHashMap<>();

    /** Keyed on the value stored on the vulnerability, which is free text. */
    @Builder.Default
    private Map<String, ColourPair> likelihood = new LinkedHashMap<>();

    /** Keyed on the value stored on the vulnerability, which is free text. */
    @Builder.Default
    private Map<String, ColourPair> impact = new LinkedHashMap<>();

    /** Keyed on the user-defined field's variable name. */
    @Builder.Default
    private Map<String, FieldColours> customFields = new LinkedHashMap<>();

    /**
     * Whether likelihood and impact carry their own colours rather than severity's.
     *
     * <p>All three are the same five levels, edited with the same picker, so one set of colours is
     * what almost every template wants — the designer shows a single group until this is set, and
     * writes the same colours into all three maps.
     *
     * <p>Stored rather than derived from whether the three maps happen to be equal. Someone who
     * splits them and has not yet changed anything would otherwise see the checkbox silently
     * uncheck itself on the next load.
     */
    @Builder.Default
    private Boolean separateRatingColours = false;

    /**
     * High-water mark for slot allocation. Stored rather than derived from the highest slot in use,
     * so that removing a field's colours cannot free its number: the sentinel for a retired slot
     * may still be painted somewhere in a template body, and reissuing it to a different field
     * would silently recolour whatever cell it sits in.
     */
    @Builder.Default
    private Integer nextCustomSlot = 4;

    /**
     * A palette for a newly created template: every severity coloured from the web UI's own
     * palette, so a report and the screen it came from agree about what Critical looks like.
     *
     * <p>The light-mode values from {@code SeverityBadge.css}, since a report is printed on white,
     * with its {@code rgba(…, 0.16)} backgrounds flattened over white into opaque hex.
     */
    public static ReportPalette defaults() {
        Map<String, ColourPair> severity = new LinkedHashMap<>();
        severity.put(VulnerabilitySeverity.CRITICAL.name(),      ColourPair.of("B91C1C", "FCE1E1"));
        severity.put(VulnerabilitySeverity.HIGH.name(),          ColourPair.of("C2410C", "FEE9DA"));
        severity.put(VulnerabilitySeverity.MEDIUM.name(),        ColourPair.of("B45309", "FDEFD8"));
        severity.put(VulnerabilitySeverity.LOW.name(),           ColourPair.of("1D4ED8", "E0EBFE"));
        severity.put(VulnerabilitySeverity.INFORMATIONAL.name(), ColourPair.of("334155", "EFF0F2"));

        // Likelihood and impact are the same five levels as severity, so they seed with the same
        // colours. Leaving them empty meant a template painting a likelihood sentinel rendered
        // black on white until someone found a setting they had no reason to know existed.
        return ReportPalette.builder()
                .severity(severity)
                .likelihood(copyOf(severity))
                .impact(copyOf(severity))
                .build();
    }

    /**
     * The slot this field owns, allocating the next free one if it has none.
     *
     * @return a slot number suitable for {@code ColourSentinels.light}/{@code dark}
     */
    public int allocateSlot(String variableName) {
        FieldColours existing = customFields().get(variableName);
        if (existing != null && existing.getSlot() != null) return existing.getSlot();

        int slot = nextCustomSlot == null ? 4 : nextCustomSlot;
        nextCustomSlot = slot + 1;
        customFields().put(variableName,
                FieldColours.builder().slot(slot).values(new LinkedHashMap<>()).build());
        return slot;
    }

    /** Drops a field's colours. Its slot number is retired, not freed — see {@link #nextCustomSlot}. */
    public void releaseSlot(String variableName) {
        customFields().remove(variableName);
    }

    /**
     * A detached copy. Cloning a report template must not leave the two sharing one palette, for
     * the same reason {@link UserDefinedField#copy()} rebuilds its dropdown options: editing one
     * template's colours would otherwise recolour the other's reports. The slot counter is carried
     * across so a clone cannot reissue a number the original has already used.
     */
    public ReportPalette copy() {
        Map<String, FieldColours> fields = new LinkedHashMap<>();
        customFields().forEach((name, colours) -> fields.put(name, colours.copy()));

        return ReportPalette.builder()
                .severity(copyOf(severity))
                .likelihood(copyOf(likelihood))
                .impact(copyOf(impact))
                .customFields(fields)
                .separateRatingColours(separateRatingColours)
                .nextCustomSlot(nextCustomSlot)
                .build();
    }

    private Map<String, FieldColours> customFields() {
        if (customFields == null) customFields = new LinkedHashMap<>();
        return customFields;
    }

    private static Map<String, ColourPair> copyOf(Map<String, ColourPair> source) {
        Map<String, ColourPair> copy = new LinkedHashMap<>();
        if (source != null) source.forEach((key, pair) -> copy.put(key, pair.copy()));
        return copy;
    }

    /** Font colour and cell fill for one value of one dimension. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColourPair {
        private String text;
        private String fill;

        public static ColourPair of(String text, String fill) {
            return ColourPair.builder().text(text).fill(fill).build();
        }

        public ColourPair copy() {
            return ColourPair.of(text, fill);
        }
    }

    /** One user-defined field's allocated slot and the colours for its values. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldColours {
        private Integer slot;

        @Builder.Default
        private Map<String, ColourPair> values = new LinkedHashMap<>();

        public FieldColours copy() {
            return FieldColours.builder().slot(slot).values(copyOf(values)).build();
        }
    }
}
