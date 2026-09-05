package com.faction.clientportal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_username", columnList = "username", unique = true),
    @Index(name = "idx_users_email", columnList = "email", unique = true)
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true)
    private String email;

    private String firstName;

    private String lastName;

    private String password;

    @Builder.Default
    private LoginOption loginOption = LoginOption.NATIVE;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> roleIds = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> teamIds = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;

    private LocalDateTime disabledAt;

    /**
     * When a failed-password lockout expires, or null when the account is not locked out.
     *
     * <p>Separate from {@link #disabledAt} on purpose: an administrator switching an account off
     * and the system locking it for a few minutes are different decisions, and a cooldown must
     * never quietly re-enable somebody a human disabled. A policy configured with no lockout
     * duration sets {@code disabledAt} instead, and then only an administrator can lift it.
     */
    private LocalDateTime lockedUntil;

    @Builder.Default
    private Integer failedLoginAttempts = 0;

    private LocalDateTime lastLogin;

    /** Random UUID identifying the uploaded profile image; null = default avatar. */
    private String profileImageId;

    /** Storage key of the uploaded profile image in MinIO. */
    private String profileImageKey;

    @Builder.Default
    private Boolean isInternal = true;

    private String organizationId;
}
