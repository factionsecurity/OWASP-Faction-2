package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.PasswordPolicy;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.PasswordPolicyRepository;
import com.faction.clientportal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The installation's password and sign-in rules.
 *
 * <p>The lockout half is what turns an unauthenticated caller who knows a username into either a
 * nuisance or a denial of service, so both shapes are pinned: a cooldown that lifts itself, and a
 * permanent lock that waits for an administrator.
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordPolicyTest extends TestContainersConfig {

    @Autowired private PasswordPolicyService policyService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordPolicyRepository policyRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        policyRepository.deleteAll();
        userRepository.save(User.builder()
                .username("kim").email("kim@test.com")
                .password(passwordEncoder.encode("correct-horse-battery"))
                .loginOption(LoginOption.NATIVE).roleIds(List.of())
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
    }

    private void setPolicy(java.util.function.UnaryOperator<PasswordPolicy.PasswordPolicyBuilder> f) {
        policyService.updatePolicy(f.apply(PasswordPolicy.builder()).build());
    }

    private void failLogin(int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> authService.login("kim", "wrong"))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    private User reload() {
        return userRepository.findByUsername("kim").orElseThrow();
    }

    // ── Composition ──────────────────────────────────────────────────────────

    @Test
    void aPasswordIsHeldToTheConfiguredRules() {
        setPolicy(b -> b.minimumLength(10).requireUppercase(true)
                .requireLowercase(true).requireDigit(true).requireSymbol(true));

        assertThatThrownBy(() -> policyService.validate("short1!A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 10 characters");
        assertThatCode(() -> policyService.validate("Longenough1!")).doesNotThrowAnyException();
    }

    @Test
    void everyBrokenRuleIsReportedAtOnce() {
        setPolicy(b -> b.minimumLength(12).requireUppercase(true)
                .requireLowercase(true).requireDigit(true).requireSymbol(true));

        // One rule at a time turns setting a password into a guessing game.
        assertThatThrownBy(() -> policyService.validate("short"))
                .hasMessageContaining("at least 12 characters")
                .hasMessageContaining("uppercase")
                .hasMessageContaining("number")
                .hasMessageContaining("symbol");
    }

    @Test
    void relaxingThePolicyRelaxesWhatIsAccepted() {
        setPolicy(b -> b.minimumLength(4).requireUppercase(false)
                .requireLowercase(false).requireDigit(false).requireSymbol(false));

        assertThatCode(() -> policyService.validate("abcd")).doesNotThrowAnyException();
    }

    // ── Lockout: cooldown ────────────────────────────────────────────────────

    @Test
    void aCooldownLocksTheAccountAndThenLetsItBack() {
        setPolicy(b -> b.maxFailedLoginAttempts(3).lockoutDurationMinutes(15));

        failLogin(3);

        User locked = reload();
        assertThat(locked.getLockedUntil()).isNotNull();
        // A cooldown must not disable the account — that is a human's decision, not a timer's.
        assertThat(locked.getDisabledAt()).isNull();
        assertThatThrownBy(() -> authService.login("kim", "correct-horse-battery"))
                .hasMessageContaining("Too many failed sign-in attempts");

        // Wind the clock past the cooldown.
        locked.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        userRepository.save(locked);

        assertThat(authService.login("kim", "correct-horse-battery")).isNotNull();
        assertThat(reload().getLockedUntil()).isNull();
        assertThat(reload().getFailedLoginAttempts()).isZero();
    }

    @Test
    void anExpiredCooldownDoesNotRelockOnTheNextTypo() {
        setPolicy(b -> b.maxFailedLoginAttempts(3).lockoutDurationMinutes(15));
        failLogin(3);
        User locked = reload();
        locked.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        userRepository.save(locked);

        // One wrong password after the cooldown expires. If the counter had survived at the limit,
        // this single mistake would lock them straight back out.
        failLogin(1);

        assertThat(reload().getLockedUntil()).isNull();
        assertThat(reload().getFailedLoginAttempts()).isEqualTo(1);
    }

    // ── Lockout: permanent ───────────────────────────────────────────────────

    @Test
    void aZeroDurationDisablesTheAccountUntilAnAdministratorActs() {
        setPolicy(b -> b.maxFailedLoginAttempts(3).lockoutDurationMinutes(0));

        failLogin(3);

        User locked = reload();
        assertThat(locked.getDisabledAt()).isNotNull();
        assertThat(locked.getLockedUntil()).isNull();
        assertThatThrownBy(() -> authService.login("kim", "correct-horse-battery"))
                .hasMessageContaining("Account is disabled");
    }

    @Test
    void lockoutCanBeSwitchedOffEntirely() {
        setPolicy(b -> b.maxFailedLoginAttempts(0));

        failLogin(10);

        User after = reload();
        assertThat(after.getDisabledAt()).isNull();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(authService.login("kim", "correct-horse-battery")).isNotNull();
    }

    @Test
    void guessingAtAnAlreadyDisabledAccountDoesNotInflateItsHistory() {
        setPolicy(b -> b.maxFailedLoginAttempts(3).lockoutDurationMinutes(0));
        User user = reload();
        user.setDisabledAt(LocalDateTime.now());
        userRepository.save(user);

        failLogin(5);

        assertThat(reload().getFailedLoginAttempts()).isZero();
    }

    @Test
    void aSuccessfulSignInForgivesEarlierMistakes() {
        setPolicy(b -> b.maxFailedLoginAttempts(3).lockoutDurationMinutes(15));

        failLogin(2);
        authService.login("kim", "correct-horse-battery");
        failLogin(2);

        // The limit means "in a row" — otherwise every long-lived account eventually trips it.
        assertThat(reload().getLockedUntil()).isNull();
    }

    // ── Policy validation ────────────────────────────────────────────────────

    @Test
    void nonsenseSettingsAreRefusedRatherThanSilentlyWeakeningTheInstall() {
        assertThatThrownBy(() -> setPolicy(b -> b.minimumLength(0)))
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> setPolicy(b -> b.maxFailedLoginAttempts(-1)))
                .hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> setPolicy(b -> b.lockoutDurationMinutes(-5)))
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void theDefaultPolicyIsACooldownNotAPermanentLock() {
        PasswordPolicy defaults = policyService.getPolicy();

        // With no rate limit in front of the login endpoint, a permanent lock by default would
        // hand any anonymous caller a way to hold an account shut.
        assertThat(defaults.getLockoutDurationMinutes()).isGreaterThan(0);
        assertThat(defaults.getMaxFailedLoginAttempts()).isGreaterThan(0);
    }
}
