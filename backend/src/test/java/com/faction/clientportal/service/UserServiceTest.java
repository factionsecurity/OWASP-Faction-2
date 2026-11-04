package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserService#requireCanEdit}: the access rule {@code updateUserDto} applies to its
 * target, exposed for edits that live beside the user record.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceTest extends TestContainersConfig {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void requireCanEditAllowsTeamScopeWithinTheTeam() {
        userRepository.save(user("lead", "team-a"));
        User target = userRepository.save(user("member", "team-a", "team-b"));

        User returned = userService.requireCanEdit(target.getId(), teamScoped("lead"));

        assertThat(returned.getId()).isEqualTo(target.getId());
    }

    @Test
    void requireCanEditRejectsTeamScopeOutsideTheTeam() {
        userRepository.save(user("lead", "team-a"));
        User target = userRepository.save(user("outsider", "team-b"));

        assertThatThrownBy(() -> userService.requireCanEdit(target.getId(), teamScoped("lead")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private User user(String username, String... teamIds) {
        return User.builder()
                .username(username)
                .email(username + "@example.com")
                .firstName("First")
                .lastName("Last")
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .teamIds(new ArrayList<>(List.of(teamIds)))
                .build();
    }

    private Authentication teamScoped(String username) {
        List<GrantedAuthority> granted = List.of(new SimpleGrantedAuthority("users:edit:team"));
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
