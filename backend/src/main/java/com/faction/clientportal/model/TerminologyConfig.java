package com.faction.clientportal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What this installation calls the things it holds.
 *
 * <p>"Organization" is the product's word, not necessarily the customer's. A consultancy's
 * organizations are their client companies; an enterprise security team's might be value streams,
 * business units, or portfolios. Making people translate the product's vocabulary into their own
 * every time they read a screen is a small tax paid constantly.
 *
 * <p>Singular and plural are both stored rather than deriving one from the other. Appending "s"
 * is right often enough to be tempting and wrong often enough to look careless — "Value Stream"
 * pluralises cleanly, "Entity" and "Business" do not.
 *
 * <p>Severity labels live here too, for the same reason: a team that runs on "P1"–"P4" or
 * "Sev-1"–"Sev-5" reads "Critical" as a translation step. Only the label moves — the
 * {@link VulnerabilitySeverity} constants, and therefore every stored row, every SLA rule, every
 * report token and both export formats, keep saying CRITICAL. There is deliberately no way to add
 * or remove a level: the enum is closed and the ranking, the SLA config and the SARIF/CycloneDX
 * mappings are all written against exactly these five.
 */
@Entity
@Table(name = "terminology_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminologyConfig {

    @Id
    private String id;

    @Builder.Default
    @Column(nullable = false)
    private String organizationSingular = "Organization";

    @Builder.Default
    @Column(nullable = false)
    private String organizationPlural = "Organizations";

    @Builder.Default
    @Column(nullable = false)
    private String subOrganizationSingular = "Sub-organization";

    @Builder.Default
    @Column(nullable = false)
    private String subOrganizationPlural = "Sub-organizations";

    @Builder.Default
    @Column(nullable = false)
    private String severityCritical = "Critical";

    @Builder.Default
    @Column(nullable = false)
    private String severityHigh = "High";

    @Builder.Default
    @Column(nullable = false)
    private String severityMedium = "Medium";

    @Builder.Default
    @Column(nullable = false)
    private String severityLow = "Low";

    @Builder.Default
    @Column(nullable = false)
    private String severityInformational = "Informational";

    /**
     * This installation's word for a severity named as free text.
     *
     * <p>Likelihood and impact are stored as strings rather than the enum, and have been written
     * both as "CRITICAL" and as "Critical" by different screens over time — so the match is
     * case-insensitive. Anything that is not one of the five (a legacy "3", free text from an
     * import) comes back exactly as it went in.
     */
    public String labelForName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return name;
        try {
            return labelFor(VulnerabilitySeverity.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException notASeverity) {
            return name;
        }
    }

    /** This installation's word for one severity. Null in, empty out — the caller renders "". */
    public String labelFor(VulnerabilitySeverity severity) {
        if (severity == null) return "";
        return switch (severity) {
            case CRITICAL      -> severityCritical;
            case HIGH          -> severityHigh;
            case MEDIUM        -> severityMedium;
            case LOW           -> severityLow;
            case INFORMATIONAL -> severityInformational;
        };
    }
}
