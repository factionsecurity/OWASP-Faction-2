package com.faction.clientportal.service;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.CreateUserRequest;
import com.faction.clientportal.dto.UpdateUserRequest;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.security.RequiresPermissionAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code disabled} flag on user create/update.
 *
 * <p>{@code disabledAt} gates login and makes an account's API keys inert, and the users
 * table already renders a "Disabled" badge from it — but nothing could set it, so the state
 * was unreachable. An import that has to stand up an assessor or owner nobody is behind yet
 * needs exactly this: an account that links and keeps history but cannot be logged into.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserDisabledFlagTest extends TestContainersConfig {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private String roleId;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();
        roleId = roleRepository.save(Role.builder().name("Imported").permissions(List.of()).build()).getId();
    }

    @Test
    void createWithDisabled_producesAnAccountThatCannotLogIn() {
        var created = userService.createUserDto(request("ghost").disabled(true).build(), superAdmin());

        assertThat(created.getDisabledAt()).isNotNull();
        assertThat(userRepository.findById(created.getId()).orElseThrow().getDisabledAt()).isNotNull();
    }

    @Test
    void createWithoutTheFlag_leavesTheAccountActive() {
        var created = userService.createUserDto(request("live").build(), superAdmin());

        assertThat(created.getDisabledAt()).isNull();

        var explicitlyEnabled = userService.createUserDto(
                request("live2").disabled(false).build(), superAdmin());
        assertThat(explicitlyEnabled.getDisabledAt()).isNull();
    }

    @Test
    void updateCanDisableAndReEnable() {
        var created = userService.createUserDto(request("swings").build(), superAdmin());

        var disabled = userService.updateUserDto(created.getId(), update("swings", true), superAdmin());
        assertThat(disabled.getDisabledAt()).isNotNull();

        var enabled = userService.updateUserDto(created.getId(), update("swings", false), superAdmin());
        assertThat(enabled.getDisabledAt()).isNull();
    }

    @Test
    void reEnablingClearsAStandingLockout() {
        // Otherwise the account comes back still locked out by a failure count nobody can see.
        var created = userService.createUserDto(request("locked").build(), superAdmin());
        User stored = userRepository.findById(created.getId()).orElseThrow();
        stored.setDisabledAt(LocalDateTime.now());
        stored.setFailedLoginAttempts(5);
        userRepository.save(stored);

        userService.updateUserDto(created.getId(), update("locked", false), superAdmin());

        User after = userRepository.findById(created.getId()).orElseThrow();
        assertThat(after.getDisabledAt()).isNull();
        assertThat(after.getFailedLoginAttempts()).isZero();
    }

    @Test
    void anUpdateThatOmitsTheFlagLeavesTheStateAlone() {
        // An ordinary "fix the surname" edit must not silently re-enable a disabled account.
        var created = userService.createUserDto(request("dormant").disabled(true).build(), superAdmin());

        var updated = userService.updateUserDto(created.getId(), update("dormant", null), superAdmin());

        assertThat(updated.getDisabledAt()).isNotNull();
    }

    @Test
    void disablingTwiceKeepsTheOriginalTimestamp() {
        var created = userService.createUserDto(request("stable").disabled(true).build(), superAdmin());
        LocalDateTime first = userRepository.findById(created.getId()).orElseThrow().getDisabledAt();

        userService.updateUserDto(created.getId(), update("stable", true), superAdmin());

        assertThat(userRepository.findById(created.getId()).orElseThrow().getDisabledAt()).isEqualTo(first);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private CreateUserRequest.CreateUserRequestBuilder request(String username) {
        return CreateUserRequest.builder()
                .username(username)
                .email(username + "@example.com")
                .firstName("First")
                .lastName("Last")
                .password("Sup3rSecret!")
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(roleId))
                .isInternal(true);
    }

    private UpdateUserRequest update(String username, Boolean disabled) {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setFirstName("First");
        request.setLastName("Last");
        request.setLoginOption(LoginOption.NATIVE);
        request.setRoleIds(List.of(roleId));
        request.setIsInternal(true);
        request.setDisabled(disabled);
        return request;
    }

    private Authentication superAdmin() {
        List<GrantedAuthority> granted =
                List.of(new SimpleGrantedAuthority(RequiresPermissionAuthorizationManager.SUPER_ADMIN));
        return new UsernamePasswordAuthenticationToken("admin", null, granted);
    }
}
