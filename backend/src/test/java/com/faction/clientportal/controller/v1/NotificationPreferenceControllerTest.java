package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.NotificationCategory;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.NotificationPreferenceRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationPreferenceControllerTest extends TestContainersConfig {

    private static final String PATH = "/api/v1/users/me/notification-preferences";

    @Autowired private MockMvc mockMvc;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        preferenceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("Basic").permissions(List.of("vulnerabilities:read:all")).build());

        aliceToken = tokenFor("pref-alice", role);
        bobToken = tokenFor("pref-bob", role);
    }

    private String tokenFor(String username, Role role) {
        userRepository.save(User.builder()
                .username(username).firstName("Test").lastName("User")
                .email(username + "@test.com").password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        return jwtService.generateToken(username,
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all")));
    }

    private String put(String token, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put(PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void everythingIsOnBeforeAnythingIsStored() throws Exception {
        // No backfill exists, so an untouched user must read as fully enabled.
        String body = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(NotificationCategory.values().length))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"inAppEnabled\":false");
        assertThat(body).doesNotContain("\"emailEnabled\":false");
        assertThat(preferenceRepository.findAll()).isEmpty();
    }

    @Test
    void mutingEmailLeavesTheBellAlone() throws Exception {
        String body = put(aliceToken,
                "{\"preferences\":[{\"category\":\"MENTION\",\"emailEnabled\":false}]}");

        assertThat(body).contains("\"category\":\"MENTION\"");
        // Only the channel that was sent may change.
        assertThat(body).contains("\"inAppEnabled\":true,\"emailEnabled\":false");

        var stored = preferenceRepository
                .findByUsernameAndCategory("pref-alice", NotificationCategory.MENTION)
                .orElseThrow();
        assertThat(stored.isEmailEnabled()).isFalse();
        assertThat(stored.isInAppEnabled()).isTrue();
    }

    @Test
    void preferencesArePerUser() throws Exception {
        // The username comes from the principal, never the request, so one user's opt-out
        // must not touch another's.
        put(aliceToken, "{\"preferences\":[{\"category\":\"MENTION\",\"emailEnabled\":false}]}");

        String bobsView = mockMvc.perform(get(PATH).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(bobsView).doesNotContain("\"emailEnabled\":false");
    }

    @Test
    void togglingBackOnUpdatesTheSameRowRatherThanAddingAnother() throws Exception {
        put(aliceToken, "{\"preferences\":[{\"category\":\"MENTION\",\"emailEnabled\":false}]}");
        put(aliceToken, "{\"preferences\":[{\"category\":\"MENTION\",\"emailEnabled\":true}]}");

        assertThat(preferenceRepository.findByUsername("pref-alice")).hasSize(1);
        assertThat(preferenceRepository
                .findByUsernameAndCategory("pref-alice", NotificationCategory.MENTION)
                .orElseThrow().isEmailEnabled()).isTrue();
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
    }
}
