package com.faction.clientportal.repository;

import com.faction.clientportal.model.ApiKey;
import com.faction.clientportal.model.ApiKeyType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    /** Lookup for authentication — by the SHA-256 hash of the presented key. */
    Optional<ApiKey> findByTokenHash(String tokenHash);

    /** A user's active (not-revoked) keys. No ordering guaranteed — pass a {@link Sort} if order matters. */
    @Query("SELECT k FROM ApiKey k WHERE k.userId = :userId AND k.revokedAt IS NULL")
    List<ApiKey> findActiveByUserId(@Param("userId") String userId);

    /** A user's active (not-revoked) keys in the requested order. */
    @Query("SELECT k FROM ApiKey k WHERE k.userId = :userId AND k.revokedAt IS NULL")
    List<ApiKey> findActiveByUserId(@Param("userId") String userId, Sort sort);

    /** Active (not-revoked) keys of a given type. No ordering guaranteed — pass a {@link Sort} if order matters. */
    @Query("SELECT k FROM ApiKey k WHERE k.keyType = :keyType AND k.revokedAt IS NULL")
    List<ApiKey> findActiveByKeyType(@Param("keyType") ApiKeyType keyType);

    /** Active (not-revoked) keys of a given type in the requested order. */
    @Query("SELECT k FROM ApiKey k WHERE k.keyType = :keyType AND k.revokedAt IS NULL")
    List<ApiKey> findActiveByKeyType(@Param("keyType") ApiKeyType keyType, Sort sort);

    /** Whether the user already has an active (not-revoked) key with this name (case-insensitive). */
    boolean existsByUserIdAndNameIgnoreCaseAndRevokedAtIsNull(String userId, String name);

    /** Whether an active (not-revoked) key of this type already has this name (case-insensitive). */
    boolean existsByKeyTypeAndNameIgnoreCaseAndRevokedAtIsNull(ApiKeyType keyType, String name);
}
