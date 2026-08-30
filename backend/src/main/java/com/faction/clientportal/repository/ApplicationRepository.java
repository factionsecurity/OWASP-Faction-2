package com.faction.clientportal.repository;

import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.ApplicationStatus;
import com.faction.clientportal.util.LikeEscaper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, String> {

    Optional<Application> findByAppId(String appId);

    Optional<Application> findByName(String name);

    /**
     * Case-insensitive lookups for the CSV sync, which matches rows against what already exists:
     * a spreadsheet writing "app-001" must find "APP-001" rather than inserting a duplicate.
     */
    Optional<Application> findByAppIdIgnoreCase(String appId);

    Optional<Application> findByNameIgnoreCase(String name);

    List<Application> findByOrganizationId(String organizationId);

    /** How many applications are attributed to a sub-organization — guards its deletion. */
    long countBySubOrganizationId(String subOrganizationId);

    List<Application> findBySubOrganizationId(String subOrganizationId);

    /**
     * Application counts for every sub-organization at once, as {@code [subOrganizationId, count]}
     * rows. The directory listing spans all organizations, so counting one division at a time would
     * be a query per row.
     */
    @Query("""
            SELECT a.subOrganizationId, COUNT(a) FROM Application a
            WHERE a.subOrganizationId IS NOT NULL
            GROUP BY a.subOrganizationId
            """)
    List<Object[]> countGroupedBySubOrganizationId();

    boolean existsByName(String name);

    // Case-insensitive substring ("contains") search over name/description/appId.
    // Anchored 'term%' matching missed anything not at the start of the name. This default
    // escapes LIKE wildcards (% and _) in the term so they match literally, then delegates
    // to the query below (paired ESCAPE '!').
    default Page<Application> searchByNameOrDescription(String searchTerm, Pageable pageable) {
        return searchByNameOrDescriptionInternal(LikeEscaper.escape(searchTerm), pageable);
    }

    @Query("""
            SELECT a FROM Application a WHERE
              LOWER(a.name) LIKE LOWER(CONCAT('%', ?1, '%')) ESCAPE '!'
              OR LOWER(a.description) LIKE LOWER(CONCAT('%', ?1, '%')) ESCAPE '!'
              OR LOWER(a.appId) LIKE LOWER(CONCAT('%', ?1, '%')) ESCAPE '!'
            """)
    Page<Application> searchByNameOrDescriptionInternal(String escapedTerm, Pageable pageable);

    /**
     * The applications list with every filter optional — a null one is a no-op, so one query backs
     * the unfiltered list and any combination of organization / division / status.
     *
     * <p>Search matches the same three columns as {@link #searchByNameOrDescription}; callers pass
     * an already-escaped {@code %term%} pattern, hence the paired {@code ESCAPE '!'}.
     */
    default Page<Application> searchFiltered(String searchTerm, String organizationId,
                                             String subOrganizationId, ApplicationStatus status,
                                             Pageable pageable) {
        String pattern = (searchTerm == null || searchTerm.isBlank())
                ? null : "%" + LikeEscaper.escape(searchTerm.trim().toLowerCase()) + "%";
        return searchFilteredInternal(pattern, organizationId, subOrganizationId, status, pageable);
    }

    @Query("""
            SELECT a FROM Application a
            WHERE (:pattern IS NULL
                   OR LOWER(a.name) LIKE :pattern ESCAPE '!'
                   OR LOWER(a.description) LIKE :pattern ESCAPE '!'
                   OR LOWER(a.appId) LIKE :pattern ESCAPE '!')
              AND (:organizationId IS NULL OR a.organizationId = :organizationId)
              AND (:subOrganizationId IS NULL OR a.subOrganizationId = :subOrganizationId)
              AND (:status IS NULL OR a.status = :status)
            """)
    Page<Application> searchFilteredInternal(String pattern, String organizationId,
                                             String subOrganizationId, ApplicationStatus status,
                                             Pageable pageable);

    @Query(value = "SELECT * FROM applications WHERE assigned_users @> CAST(CONCAT('[{\"userId\":\"', ?1, '\"}]') AS jsonb) AND deleted_at IS NULL", nativeQuery = true)
    List<Application> findByAssignedUsersUserId(String userId);

    List<Application> findByOrganizationIdIn(java.util.Collection<String> organizationIds);
}
