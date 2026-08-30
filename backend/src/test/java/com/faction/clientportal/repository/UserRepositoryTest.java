package com.faction.clientportal.repository;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")

class UserRepositoryTest extends TestContainersConfig {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void saveAndFindByUsername_Success() {
        User user = User.builder()
                .username("testuser")
                .password("hashedPassword")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of("role1", "role2"))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();

        User savedUser = userRepository.save(user);
        assertThat(savedUser.getId()).isNotNull();

        Optional<User> foundUser = userRepository.findByUsername("testuser");
        assertThat(foundUser).isPresent();
        assertThat(foundUser.get().getUsername()).isEqualTo("testuser");
        assertThat(foundUser.get().getIsInternal()).isTrue();
        assertThat(foundUser.get().getRoleIds()).containsExactly("role1", "role2");
    }

    @Test
    void existsByUsername_ReturnsTrue_WhenUserExists() {
        User user = User.builder()
                .username("testuser")
                .password("hashedPassword")
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        userRepository.save(user);

        boolean exists = userRepository.existsByUsername("testuser");
        assertThat(exists).isTrue();
    }

    @Test
    void existsByUsername_ReturnsFalse_WhenUserDoesNotExist() {
        boolean exists = userRepository.existsByUsername("nonexistent");
        assertThat(exists).isFalse();
    }

    @Test
    void saveExternalUser_WithOrganizationId() {
        User externalUser = User.builder()
                .username("externaluser")
                .password("hashedPassword")
                .loginOption(LoginOption.SAML2)
                .isInternal(false)
                .organizationId("org123")
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();

        User savedUser = userRepository.save(externalUser);
        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getIsInternal()).isFalse();
        assertThat(savedUser.getOrganizationId()).isEqualTo("org123");
    }

    @Test
    void existsByEmailIgnoreCase_matchesRegardlessOfCase() {
        // Bob@x.com and bob@x.com are one mailbox as far as SMTP is concerned. Allowing
        // both to exist made reply-by-email ambiguous, since inbound mail resolves its
        // sender by address and two users could match.
        userRepository.save(User.builder()
                .username("caseuser")
                .email("Bob.Smith@Example.COM")
                .password("hashed")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of())
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        assertThat(userRepository.existsByEmailIgnoreCase("bob.smith@example.com")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("BOB.SMITH@EXAMPLE.COM")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("Bob.Smith@Example.COM")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("someone.else@example.com")).isFalse();
    }
}