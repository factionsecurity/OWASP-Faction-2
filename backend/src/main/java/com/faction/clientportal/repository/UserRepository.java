package com.faction.clientportal.repository;

import com.faction.clientportal.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);

    /** Bulk form of {@link #findByUsername}, for enriching a page of rows without per-row lookups. */
    List<User> findByUsernameIn(Collection<String> usernames);

    Optional<User> findByProfileImageId(String profileImageId);

    boolean existsByUsername(String username);

    /**
     * Case-insensitive by design. SMTP treats the domain as case-insensitive and every
     * mainstream provider treats the local part that way too, so Bob@x.com and bob@x.com
     * are one mailbox. Allowing both to exist made reply-by-email attribution ambiguous:
     * inbound mail matches the sender against this address, and two users could match.
     */
    boolean existsByEmailIgnoreCase(String email);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT(?1, '%'))")
    Page<User> findByUsernameContainingIgnoreCase(String username, Pageable pageable);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(u.firstName) LIKE LOWER(CONCAT(?1, '%')) OR LOWER(u.lastName) LIKE LOWER(CONCAT(?1, '%'))")
    Page<User> searchByUsernameOrEmailOrFirstNameOrLastName(String searchTerm, Pageable pageable);

    @Query(value = "SELECT * FROM users WHERE role_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) AND deleted_at IS NULL", nativeQuery = true)
    List<User> findByRoleIdsContaining(String roleId);

    @Query(value = "SELECT * FROM users WHERE team_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) AND deleted_at IS NULL", nativeQuery = true)
    List<User> findByTeamIdsContaining(String teamId);

    /** External, live (not deleted, not disabled) members of an organization — the notification audience. */
    @Query(value = "SELECT * FROM users WHERE organization_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) "
            + "AND is_internal = false AND deleted_at IS NULL AND disabled_at IS NULL", nativeQuery = true)
    List<User> findLiveExternalByOrganizationId(String organizationId);

    /** The same audience for a sub-organization. */
    @Query(value = "SELECT * FROM users WHERE sub_organization_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) "
            + "AND is_internal = false AND deleted_at IS NULL AND disabled_at IS NULL", nativeQuery = true)
    List<User> findLiveExternalBySubOrganizationId(String subOrganizationId);

    /**
     * External members of an organization, disabled included, deleted excluded. Disabling is
     * temporary — a lockout, or an import nobody has activated — and the person is still a
     * colleague you would name in a comment thread. Deletion is not: that account has left.
     */
    @Query(value = "SELECT * FROM users WHERE organization_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) "
            + "AND is_internal = false AND deleted_at IS NULL", nativeQuery = true)
    List<User> findExternalByOrganizationId(String organizationId);

    @Query(value = "SELECT * FROM users WHERE sub_organization_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) "
            + "AND is_internal = false AND deleted_at IS NULL", nativeQuery = true)
    List<User> findExternalBySubOrganizationId(String subOrganizationId);

    /**
     * Ids of the members of an organization or of any of its sub-organizations — the user-list
     * filter. {@code subOrgIds} must be non-empty (pass a placeholder when the organization has none).
     */
    @Query(value = "SELECT id FROM users WHERE organization_ids @> CAST(CONCAT('[\"', :orgId, '\"]') AS jsonb) "
            + "OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(sub_organization_ids) s WHERE s IN (:subOrgIds))",
            nativeQuery = true)
    List<String> findIdsByOrganizationMembership(@Param("orgId") String orgId,
                                                 @Param("subOrgIds") Collection<String> subOrgIds);

    @Query(value = "SELECT count(*) FROM users WHERE organization_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) "
            + "AND deleted_at IS NULL", nativeQuery = true)
    long countByOrganizationIdsContaining(String organizationId);

    @Query(value = "SELECT count(*) FROM users WHERE sub_organization_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb) "
            + "AND deleted_at IS NULL", nativeQuery = true)
    long countBySubOrganizationIdsContaining(String subOrganizationId);

    /**
     * Ids of everyone holding a role / belonging to a team. Used to narrow the user list: the
     * membership lists are jsonb, so containment has to be a native query, and resolving to ids
     * first keeps the list query itself JPQL — which is what lets {@code Pageable} sorting work.
     *
     * <p>Soft-deleted users are deliberately included: the list shows them with a "Deleted"
     * status, so filtering by role must not silently drop them.
     */
    @Query(value = "SELECT id FROM users WHERE role_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb)",
           nativeQuery = true)
    List<String> findIdsByRoleId(String roleId);

    @Query(value = "SELECT id FROM users WHERE team_ids @> CAST(CONCAT('[\"', ?1, '\"]') AS jsonb)",
           nativeQuery = true)
    List<String> findIdsByTeamId(String teamId);

    /**
     * The user list, with every filter optional. A null filter is a no-op, so one query serves the
     * unfiltered list and any combination of them.
     *
     * @param search  lower-cased prefix pattern (e.g. {@code "jo%"}), or null for no search
     * @param ids     ids to restrict to, meaningful only when {@code filterByIds} is true
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:search IS NULL
                   OR LOWER(u.username) LIKE :search OR LOWER(u.email) LIKE :search
                   OR LOWER(u.firstName) LIKE :search OR LOWER(u.lastName) LIKE :search)
              AND (:isInternal IS NULL OR u.isInternal = :isInternal)
              AND (:filterByIds = FALSE OR u.id IN :ids)
            """)
    Page<User> searchFiltered(String search,
                              Boolean isInternal,
                              boolean filterByIds,
                              Collection<String> ids,
                              Pageable pageable);

    java.util.Optional<User> findByEmailIgnoreCase(String email);

    List<User> findAllByUsernameIgnoreCaseAndDeletedAtIsNull(String username);
    List<User> findAllByEmailIgnoreCaseAndDeletedAtIsNull(String email);
}
