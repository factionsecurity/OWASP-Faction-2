package com.faction.clientportal.service;

import com.faction.clientportal.model.PasswordPolicy;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.PasswordPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The install's password and sign-in rules, and the two decisions that depend on them: whether a
 * proposed password is acceptable, and what a failed sign-in does to the account.
 *
 * <p>Both live here rather than at the call sites so every path that sets a password — an admin
 * creating a user, a user changing their own, a reset link — is held to the same rules. A policy
 * enforced in three places out of four is not a policy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordPolicyService {

    static final String SINGLETON_ID = "singleton";

    private final PasswordPolicyRepository repository;

    /** The configured policy, creating the default row the first time it is asked for. */
    public PasswordPolicy getPolicy() {
        return repository.findById(SINGLETON_ID)
                .orElseGet(() -> repository.save(
                        PasswordPolicy.builder().id(SINGLETON_ID).build()));
    }

    public PasswordPolicy updatePolicy(PasswordPolicy policy) {
        policy.setId(SINGLETON_ID);
        // Nonsense values would silently weaken the install rather than fail loudly.
        if (policy.getMinimumLength() < 1) {
            throw new IllegalArgumentException("Minimum password length must be at least 1");
        }
        if (policy.getMinimumLength() > 256) {
            throw new IllegalArgumentException("Minimum password length cannot exceed 256");
        }
        if (policy.getMaxFailedLoginAttempts() < 0) {
            throw new IllegalArgumentException(
                    "Failed sign-in limit cannot be negative; use 0 to switch lockout off");
        }
        if (policy.getLockoutDurationMinutes() < 0) {
            throw new IllegalArgumentException(
                    "Lockout duration cannot be negative; use 0 to lock until an administrator lifts it");
        }
        PasswordPolicy saved = repository.save(policy);
        log.info("Password policy updated: minLength={}, maxFailedAttempts={}, lockoutMinutes={}",
                saved.getMinimumLength(), saved.getMaxFailedLoginAttempts(),
                saved.getLockoutDurationMinutes());
        return saved;
    }

    // ── Composition ───────────────────────────────────────────────────────────

    /**
     * Checks a proposed password, listing everything wrong with it at once.
     *
     * <p>All the failures rather than the first, because a rule at a time turns setting a password
     * into a guessing game.
     */
    public void validate(String password) {
        PasswordPolicy policy = getPolicy();
        List<String> problems = new ArrayList<>();

        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < policy.getMinimumLength()) {
            problems.add("be at least " + policy.getMinimumLength() + " characters");
        }
        if (policy.isRequireUppercase() && password.chars().noneMatch(Character::isUpperCase)) {
            problems.add("contain an uppercase letter");
        }
        if (policy.isRequireLowercase() && password.chars().noneMatch(Character::isLowerCase)) {
            problems.add("contain a lowercase letter");
        }
        if (policy.isRequireDigit() && password.chars().noneMatch(Character::isDigit)) {
            problems.add("contain a number");
        }
        if (policy.isRequireSymbol()
                && password.chars().noneMatch(c -> !Character.isLetterOrDigit(c))) {
            problems.add("contain a symbol");
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Password must " + String.join(", ", problems));
        }
    }

    // ── Sign-in attempts ──────────────────────────────────────────────────────

    /**
     * Whether a lockout is currently in force, clearing an expired one as a side effect.
     *
     * <p>Cleared lazily on the next sign-in attempt rather than by a scheduled sweep: the only
     * moment the answer matters is when somebody tries to sign in, and a job that unlocks accounts
     * on a timer is one more thing to get wrong.
     *
     * @return true when the account is locked right now
     */
    public boolean isLockedOut(User user) {
        if (user.getLockedUntil() == null) {
            return false;
        }
        if (user.getLockedUntil().isAfter(LocalDateTime.now())) {
            return true;
        }
        user.setLockedUntil(null);
        // The count goes too: the lockout was the punishment, and leaving the counter at the limit
        // would lock them out again on their next single mistake.
        user.setFailedLoginAttempts(0);
        return false;
    }

    /**
     * Records a wrong password and applies the policy.
     *
     * <p>Which of the two outcomes depends on the configured duration: a cooldown sets
     * {@code lockedUntil} and lifts itself, while a duration of 0 disables the account outright and
     * an administrator has to re-enable it.
     *
     * @return true when this attempt locked the account
     */
    public boolean registerFailedAttempt(User user) {
        PasswordPolicy policy = getPolicy();
        int attempts = (user.getFailedLoginAttempts() == null ? 0 : user.getFailedLoginAttempts()) + 1;
        user.setFailedLoginAttempts(attempts);

        if (policy.getMaxFailedLoginAttempts() <= 0 || attempts < policy.getMaxFailedLoginAttempts()) {
            return false;
        }
        if (policy.getLockoutDurationMinutes() > 0) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(policy.getLockoutDurationMinutes()));
            log.warn("Account {} locked for {} minute(s) after {} failed sign-in attempts",
                    user.getUsername(), policy.getLockoutDurationMinutes(), attempts);
        } else {
            user.setDisabledAt(LocalDateTime.now());
            log.warn("Account {} disabled after {} failed sign-in attempts — an administrator must "
                    + "re-enable it", user.getUsername(), attempts);
        }
        return true;
    }

    /** Clears the attempt count and any lockout, after a successful sign-in or an admin unlock. */
    public void clearFailedAttempts(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
    }
}
