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
}
