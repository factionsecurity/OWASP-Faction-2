package com.faction.clientportal.repository;

import com.faction.clientportal.model.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, String> {

    Optional<Organization> findByName(String name);

    /** Names are matched case-insensitively when an import decides whether to create one. */
    Optional<Organization> findByNameIgnoreCase(String name);

    boolean existsByName(String name);

    @Query("SELECT o FROM Organization o WHERE LOWER(o.name) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(o.description) LIKE LOWER(CONCAT(?1, '%'))")
    Page<Organization> searchByNameOrDescription(String searchTerm, Pageable pageable);

    @Query(value = "SELECT * FROM organizations WHERE assigned_users @> CAST(CONCAT('[{\"userId\":\"', ?1, '\"}]') AS jsonb)", nativeQuery = true)
    List<Organization> findByAssignedUsersUserId(String userId);
}
