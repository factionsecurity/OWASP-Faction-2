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
 * The install's password and sign-in rules, set by an administrator.
 *
 * <p>A single row. These were previously hard-coded or read from environment variables, which put
 * them out of reach of the person actually accountable for them — and every install is a different
 * organisation with its own standard to meet.
 */
@Entity
@Table(name = "password_policy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordPolicy {

    @Id
    private String id;

    // ── Sign-in attempts ──────────────────────────────────────────────────────

    /**
     * Consecutive failed passwords before the account is locked, or 0 to never lock out.
     *
     * <p>Counted per account and reset by a successful sign-in, so it means "in a row", not "ever".
     */
    @Builder.Default
    @Column(nullable = false)
    private int maxFailedLoginAttempts = 5;

    /**
     * How long a lockout lasts, or 0 for "until an administrator lifts it".
     *
     * <p>This is the setting worth thinking about. A permanent lockout is the stronger control and
     * the right answer when sign-ins are watched, but with no rate limit in front of it, anyone who
     * knows a username can keep that account locked out indefinitely and only an administrator can
     * undo it. A cooldown of a few minutes stops password guessing just as effectively — the
     * guessing rate collapses either way — without handing an anonymous caller that lever. The
     * default is a cooldown for exactly that reason; set 0 deliberately, not by inheriting it.
     */
    @Builder.Default
    @Column(nullable = false)
    private int lockoutDurationMinutes = 15;

    // ── Password composition ──────────────────────────────────────────────────

    @Builder.Default
    @Column(nullable = false)
    private int minimumLength = 12;

    @Builder.Default
    @Column(nullable = false)
    private boolean requireUppercase = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean requireLowercase = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean requireDigit = true;

    /**
     * Whether a password must contain something that is not a letter or a digit.
     *
     * <p>Off by default. Length does far more for strength than a mandatory punctuation mark, and
     * the requirement mostly produces a predictable "!" on the end — but plenty of standards ask
     * for it, so it is here to be switched on.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean requireSymbol = false;
}
