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

    boolean existsByAppId(String appId);

    /**
     * The highest number already used by application ids matching this LIKE pattern ({@code "ASMT-%"}),
     * or 0 when none do. Ids that don't end in a number are ignored: generated ids are prefix-number,
     * while an id somebody typed or imported can hold anything.
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(app_id FROM '[0-9]+$') AS BIGINT)), 0)
            FROM applications
            WHERE app_id LIKE :prefixPattern
            """, nativeQuery = true)
    long highestAppIdNumber(@org.springframework.data.repository.query.Param("prefixPattern") String prefixPattern);

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

    // Case-insensitive substring ("contains") search over name/appId. Descriptions are not
    // searched: a term like "UK" matched prose in descriptions and buried the applications whose
    // names actually carried it. Anchored 'term%' matching missed anything not at the start of the
    // name. This default escapes LIKE wildcards (% and _) in the term so they match literally,
    // then delegates to the query below (paired ESCAPE '!').
    default Page<Application> searchByNameOrAppId(String searchTerm, Pageable pageable) {
        return searchByNameOrAppIdInternal(LikeEscaper.escape(searchTerm), pageable);
    }

    @Query("""
            SELECT a FROM Application a WHERE
              LOWER(a.name) LIKE LOWER(CONCAT('%', ?1, '%')) ESCAPE '!'
              OR LOWER(a.appId) LIKE LOWER(CONCAT('%', ?1, '%')) ESCAPE '!'
            """)
    Page<Application> searchByNameOrAppIdInternal(String escapedTerm, Pageable pageable);

    /**
     * The applications list with every filter optional — a null or empty set is a no-op, so one query
     * backs the unfiltered list and any combination of organizations / divisions / statuses, each
     * matched as "any of".
     *
     * <p>Search matches application id, name, organization name, status, technologies and owner
     * (the owner object and the legacy owner name/email) — never the description; callers pass
     * an already-escaped {@code %term%} pattern, hence the paired {@code ESCAPE '!'}. An unused set
     * still needs a non-empty placeholder for the {@code IN} to parse; its flag keeps it inert.
     */
    default Page<Application> searchFiltered(String searchTerm, java.util.Collection<String> organizationIds,
                                             java.util.Collection<String> subOrganizationIds,
                                             java.util.Collection<ApplicationStatus> statuses,
                                             Pageable pageable) {
        String term = (searchTerm == null || searchTerm.isBlank()) ? null : searchTerm.trim().toLowerCase();
        String pattern = term == null ? null : "%" + LikeEscaper.escape(term) + "%";
        // Status is stored as an ordinal and technologies / the owner object as jsonb, none of which
        // a JPQL LIKE can reach — resolve those matches first and hand them to the query as sets.
        java.util.List<ApplicationStatus> statusMatches = term == null ? java.util.List.of() : statusesMatching(term);
        java.util.List<String> jsonMatches = pattern == null ? java.util.List.of() : findIdsMatchingJsonSearch(pattern);
        boolean byOrg = organizationIds != null && !organizationIds.isEmpty();
        boolean bySub = subOrganizationIds != null && !subOrganizationIds.isEmpty();
        boolean byStatus = statuses != null && !statuses.isEmpty();
        return searchFilteredInternal(pattern,
                !statusMatches.isEmpty(), statusMatches.isEmpty() ? java.util.List.of(ApplicationStatus.values()[0]) : statusMatches,
                !jsonMatches.isEmpty(), jsonMatches.isEmpty() ? java.util.List.of("") : jsonMatches,
                byOrg, byOrg ? organizationIds : java.util.List.of(""),
                bySub, bySub ? subOrganizationIds : java.util.List.of(""),
                byStatus, byStatus ? statuses : java.util.List.of(ApplicationStatus.values()[0]),
                pageable);
    }

    /** Statuses whose name contains the (lower-cased) search term, e.g. "decomm" → DECOMMISSIONED. */
    static java.util.List<ApplicationStatus> statusesMatching(String lowerTerm) {
        return java.util.Arrays.stream(ApplicationStatus.values())
                .filter(s -> s.name().toLowerCase().contains(lowerTerm))
                .toList();
    }

    /** Ids of applications whose technologies or owner object (name or email) contain the pattern. */
    @Query(value = """
            SELECT a.id FROM applications a
            WHERE LOWER(a.app_owner ->> 'fullName') LIKE :pattern ESCAPE '!'
               OR LOWER(a.app_owner ->> 'email') LIKE :pattern ESCAPE '!'
               OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                              CASE WHEN jsonb_typeof(a.technologies) = 'array'
                                   THEN a.technologies ELSE '[]'::jsonb END) AS tech(value)
                          WHERE LOWER(tech.value) LIKE :pattern ESCAPE '!')
            """, nativeQuery = true)
    java.util.List<String> findIdsMatchingJsonSearch(@org.springframework.data.repository.query.Param("pattern") String pattern);

    @Query("""
            SELECT a FROM Application a
            WHERE (:pattern IS NULL
                   OR LOWER(a.name) LIKE :pattern ESCAPE '!'
                   OR LOWER(a.appId) LIKE :pattern ESCAPE '!'
                   OR LOWER(a.ownerName) LIKE :pattern ESCAPE '!'
                   OR LOWER(a.ownerEmail) LIKE :pattern ESCAPE '!'
                   OR a.organizationId IN (SELECT o.id FROM Organization o
                                           WHERE LOWER(o.name) LIKE :pattern ESCAPE '!')
                   OR (:byStatusText = TRUE AND a.status IN :statusMatches)
                   OR (:byJsonMatch = TRUE AND a.id IN :jsonMatches))
              AND (:byOrg = FALSE OR a.organizationId IN :organizationIds)
              AND (:bySub = FALSE OR a.subOrganizationId IN :subOrganizationIds)
              AND (:byStatus = FALSE OR a.status IN :statuses)
            """)
    Page<Application> searchFilteredInternal(String pattern,
                                             boolean byStatusText, java.util.Collection<ApplicationStatus> statusMatches,
                                             boolean byJsonMatch, java.util.Collection<String> jsonMatches,
                                             boolean byOrg, java.util.Collection<String> organizationIds,
                                             boolean bySub, java.util.Collection<String> subOrganizationIds,
                                             boolean byStatus, java.util.Collection<ApplicationStatus> statuses,
                                             Pageable pageable);

    @Query(value = "SELECT * FROM applications WHERE assigned_users @> CAST(CONCAT('[{\"userId\":\"', ?1, '\"}]') AS jsonb) AND deleted_at IS NULL", nativeQuery = true)
    List<Application> findByAssignedUsersUserId(String userId);

    List<Application> findByOrganizationIdIn(java.util.Collection<String> organizationIds);
}
