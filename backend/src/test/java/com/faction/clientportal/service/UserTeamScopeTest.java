package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Team scoping on the user list and single-user reads.
 *
 * <p>The regression these cover: the "may reach every user" check used to accept any authority
 * <em>containing</em> {@code ":all"}, so an unrelated grant on the same role — {@code
 * applications:read:all}, which every pentester role carries — silently turned {@code
 * users:read:team} into unrestricted access to every user in the system.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserTeamScopeTest extends TestContainersConfig {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void teamScopedUser_withAnUnrelatedAllGrant_stillSeesOnlyTeammates() {
        user("me", "team-a");
        user("teammate", "team-a");
        user("stranger", "team-b");

        var names = usernames(userService.searchUsersPaginated(null, PAGE,
                auth("me",
                        Permission.USERS_READ_TEAM.getPermission(),
                        // unrelated ":all" — must not widen the *user* scope
                        Permission.APPLICATIONS_READ_ALL.getPermission())));

        assertThat(names).containsExactlyInAnyOrder("me", "teammate");
    }

    @Test
    void teamScopedUser_seesOnlyTeammates() {
        user("me", "team-a");
        user("teammate", "team-a");
        user("stranger", "team-b");

        var names = usernames(userService.searchUsersPaginated(null, PAGE,
                auth("me", Permission.USERS_READ_TEAM.getPermission())));

        assertThat(names).containsExactlyInAnyOrder("me", "teammate");
    }

    @Test
    void usersReadAll_seesEveryone() {
        user("me", "team-a");
        user("stranger", "team-b");

        var names = usernames(userService.searchUsersPaginated(null, PAGE,
                auth("me", Permission.USERS_READ_ALL.getPermission())));

        assertThat(names).containsExactlyInAnyOrder("me", "stranger");
    }

    @Test
    void superAdmin_seesEveryone() {
        user("me", "team-a");
        user("stranger", "team-b");

        var names = usernames(userService.searchUsersPaginated(null, PAGE,
                auth("me", RequiresPermissionAuthorizationManager.SUPER_ADMIN)));

        assertThat(names).containsExactlyInAnyOrder("me", "stranger");
    }

    @Test
    void findUserById_withAnUnrelatedAllGrant_stillDeniesANonTeammate() {
        user("me", "team-a");
        var stranger = user("stranger", "team-b");
        var teammate = user("teammate", "team-a");
        var authentication = auth("me",
                Permission.USERS_READ_TEAM.getPermission(),
                Permission.APPLICATIONS_READ_ALL.getPermission());

        assertThat(userService.findUserById(teammate.getId(), authentication).getUsername())
                .isEqualTo("teammate");
        assertThatThrownBy(() -> userService.findUserById(stranger.getId(), authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findUserById_withUsersReadAll_reachesAnyone() {
        user("me", "team-a");
        var stranger = user("stranger", "team-b");

        assertThat(userService.findUserById(stranger.getId(),
                auth("me", Permission.USERS_READ_ALL.getPermission())).getUsername())
                .isEqualTo("stranger");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<String> usernames(org.springframework.data.domain.Page<com.faction.clientportal.dto.UserDto> page) {
        return page.getContent().stream().map(com.faction.clientportal.dto.UserDto::getUsername).toList();
    }

    private User user(String username, String... teamIds) {
        return userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE)
                .teamIds(List.of(teamIds)).isInternal(true)
                .failedLoginAttempts(0).createdAt(LocalDateTime.now()).build());
    }

    private Authentication auth(String username, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
        return new UsernamePasswordAuthenticationToken(username, null, granted);
    }
}
