package com.faction.clientportal.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A division within an {@link Organization} — a business unit, subsidiary or region — that
 * applications can be attributed to.
 *
 * <p>Deliberately <em>not</em> a child Organization. An application keeps pointing its
 * {@code organizationId} at the owning organization and carries an optional
 * {@code subOrganizationId} alongside it, so the organization that owns the data never changes
 * and every existing access check ({@code AccessScopeService} and the org-scoped queries) keeps
 * working unchanged. A sub-organization is an attribution, not an access boundary: anyone who can
 * see the organization can see all of it.
 *
 * <p>Names are unique within an organization, not globally — two organizations may each have an
 * "EMEA".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sub_organizations", indexes = {
    @Index(name = "idx_sub_organizations_organizationid", columnList = "organization_id"),
    @Index(name = "idx_sub_organizations_org_name", columnList = "organization_id, name", unique = true)
})
public class SubOrganization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The organization this division belongs to. Never null. */
    @Column(nullable = false)
    private String organizationId;

    @Column(nullable = false)
    private String name;

    private String description;

    private String createdBy;
    private String lastUpdatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
