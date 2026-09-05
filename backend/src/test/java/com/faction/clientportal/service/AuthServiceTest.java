package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceTest extends TestContainersConfig {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Role testRole;
    private User testUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();

        testRole = Role.builder()
                .name("TestRole")
                .description("Test role")
                .permissions(List.of("test:read", "test:write"))
                .build();
        testRole = roleRepository.save(testRole);

        testUser = User.builder()
                .username("testuser")
                .password(passwordEncoder.encode("testpass"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(testRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    void login_WithValidCredentials_ReturnsToken() {
        String token = authService.login("testuser", "testpass");

        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();

        User updatedUser = userRepository.findByUsername("testuser").orElseThrow();
        assertThat(updatedUser.getFailedLoginAttempts()).isZero();
        assertThat(updatedUser.getLastLogin()).isNotNull();
    }

    @Test
    void login_WithInvalidPassword_ThrowsException() {
        assertThatThrownBy(() -> authService.login("testuser", "wrongpass"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid username or password");

        User updatedUser = userRepository.findByUsername("testuser").orElseThrow();
        assertThat(updatedUser.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void login_WithInvalidUsername_ThrowsException() {
        assertThatThrownBy(() -> authService.login("nonexistent", "testpass"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    void login_WithDisabledUser_ThrowsException() {
        testUser.setDisabledAt(LocalDateTime.now());
        userRepository.save(testUser);

        assertThatThrownBy(() -> authService.login("testuser", "testpass"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Account is disabled");
    }

    // ── Password lockout ────────────────────────────────────────────────────
    // The behaviour now comes from the configurable policy, so these set it explicitly rather
    // than depending on whatever the default happens to be. The policy's own rules — cooldown
    // versus permanent, composition — are covered in PasswordPolicyTest; what is asserted here is
    // that AuthService applies whatever it is told.

    private static final int LOCKOUT_AT = 5;

    @Autowired private PasswordPolicyService passwordPolicyService;
    @Autowired private com.faction.clientportal.repository.PasswordPolicyRepository passwordPolicyRepository;

    /** A permanent lock at the limit, which is what these tests were written against. */
    private void useLockUntilAdminPolicy() {
        passwordPolicyRepository.deleteAll();
        passwordPolicyService.updatePolicy(
                com.faction.clientportal.model.PasswordPolicy.builder()
                        .maxFailedLoginAttempts(LOCKOUT_AT)
                        .lockoutDurationMinutes(0)
                        .build());
    }

    private void failLogin(int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> authService.login("testuser", "wrongpass"))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    void reachingTheFailedAttemptLimit_disablesTheAccount() {
        useLockUntilAdminPolicy();
        failLogin(LOCKOUT_AT);

        User locked = userRepository.findByUsername("testuser").orElseThrow();
        assertThat(locked.getDisabledAt()).isNotNull();
        assertThat(locked.getFailedLoginAttempts()).isEqualTo(LOCKOUT_AT);

        // And the right password no longer gets them in — only an admin can lift it.
        assertThatThrownBy(() -> authService.login("testuser", "testpass"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Account is disabled");
    }

    @Test
    void oneAttemptShortOfTheLimit_leavesTheAccountUsable() {
        useLockUntilAdminPolicy();
        failLogin(LOCKOUT_AT - 1);

        assertThat(userRepository.findByUsername("testuser").orElseThrow().getDisabledAt()).isNull();
        assertThat(authService.login("testuser", "testpass")).isNotNull();
    }

    @Test
    void aSuccessfulLoginResetsTheCount_soTheLimitMeansConsecutiveFailures() {
        useLockUntilAdminPolicy();
        failLogin(LOCKOUT_AT - 1);
        authService.login("testuser", "testpass");
        assertThat(userRepository.findByUsername("testuser").orElseThrow().getFailedLoginAttempts()).isZero();

        // Starting over: the next few misses must not tip an already-forgiven run over the edge.
        failLogin(LOCKOUT_AT - 1);
        assertThat(userRepository.findByUsername("testuser").orElseThrow().getDisabledAt()).isNull();
    }

    @Test
    void failedAttemptsAgainstAnAlreadyDisabledAccountAreNotCounted() {
        useLockUntilAdminPolicy();
        // An admin switched this one off by hand; the counter is what tells them apart from a
        // lockout, so guessing at a disabled account must not manufacture one.
        testUser.setDisabledAt(LocalDateTime.now());
        userRepository.save(testUser);

        failLogin(3);

        User after = userRepository.findByUsername("testuser").orElseThrow();
        assertThat(after.getFailedLoginAttempts()).isZero();
    }

    @Test
    void reEnablingClearsTheLockoutAndTheCounter() {
        useLockUntilAdminPolicy();
        failLogin(LOCKOUT_AT);

        // What the Users page's re-enable does: clear disabledAt and the failed count together.
        // Without the second part they would be locked out again on the next typo.
        User locked = userRepository.findByUsername("testuser").orElseThrow();
        locked.setDisabledAt(null);
        locked.setFailedLoginAttempts(0);
        userRepository.save(locked);

        assertThat(authService.login("testuser", "testpass")).isNotNull();
    }

    @Test
    void getPermissions_ReturnsUserPermissions() {
        List<String> permissions = authService.getPermissions(testUser);

        assertThat(permissions).containsExactlyInAnyOrder("test:read", "test:write");
    }
}
