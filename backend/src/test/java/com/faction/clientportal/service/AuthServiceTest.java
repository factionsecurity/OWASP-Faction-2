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

    @Test
    void getPermissions_ReturnsUserPermissions() {
        List<String> permissions = authService.getPermissions(testUser);

        assertThat(permissions).containsExactlyInAnyOrder("test:read", "test:write");
    }
}
