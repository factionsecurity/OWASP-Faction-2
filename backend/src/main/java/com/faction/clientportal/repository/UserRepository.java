package com.faction.clientportal.repository;

import com.faction.clientportal.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    /**
     * External users whose home organization is this one, excluding deleted and disabled
     * accounts. Resolves the organization-access notification audience.
     *
     * <p>{@code isInternal = false} because only external users are given an organization —
     * that is a convention rather than something {@code UserService} enforces, so it is
     * stated here explicitly instead of inferred from the organization alone. A null
     * {@code isInternal} does not match, which matches the entity's default of true:
     * unmarked accounts are treated as staff and left out.
     *
     * <p>Deleted and disabled accounts are excluded at the query — a deactivated account
     * must not keep receiving mail.
     */
    List<User> findByOrganizationIdAndIsInternalFalseAndDeletedAtIsNullAndDisabledAtIsNull(
            String organizationId);

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
              AND (:organizationId IS NULL OR u.organizationId = :organizationId)
              AND (:isInternal IS NULL OR u.isInternal = :isInternal)
              AND (:filterByIds = FALSE OR u.id IN :ids)
            """)
    Page<User> searchFiltered(String search,
                              String organizationId,
                              Boolean isInternal,
                              boolean filterByIds,
                              Collection<String> ids,
                              Pageable pageable);

    java.util.Optional<User> findByEmailIgnoreCase(String email);
}
