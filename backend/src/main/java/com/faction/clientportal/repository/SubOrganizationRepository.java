package com.faction.clientportal.repository;

import com.faction.clientportal.model.SubOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubOrganizationRepository extends JpaRepository<SubOrganization, String> {

    List<SubOrganization> findByOrganizationIdOrderByNameAsc(String organizationId);

    List<SubOrganization> findByOrganizationIdIn(Collection<String> organizationIds);

    /** Names are unique within an organization, not globally. */
    Optional<SubOrganization> findByOrganizationIdAndNameIgnoreCase(String organizationId, String name);

    List<SubOrganization> findAllByOrderByNameAsc();

    List<SubOrganization> findByOrganizationIdInOrderByNameAsc(Collection<String> organizationIds);

    /**
     * Every division with this name, across organizations. Names are unique per organization, so
     * more than one hit means the name is ambiguous and the caller must pick.
     */
    List<SubOrganization> findByNameIgnoreCase(String name);
}
