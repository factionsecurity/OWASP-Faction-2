package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.MentionTargetType;
import com.faction.clientportal.model.Notification;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.NotificationRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String token;
    private String vulnOnlyToken;
    private String username;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        username = "notif-test-user";

        Role role = roleRepository.save(Role.builder()
                .name("User")
                .permissions(List.of("vulnerabilities:read:all", "applications:read:all",
                        "assessments:read:all"))
                .build());

        User user = userRepository.save(User.builder()
                .username(username)
                .firstName("Notif")
                .lastName("Test")
                .email("notif@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        // Privileges on all three resources, so every mentions section is visible.
        token = jwtService.generateToken(user.getUsername(),
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all"),
                        new SimpleGrantedAuthority("applications:read:all"),
                        new SimpleGrantedAuthority("assessments:read:all")));

        // Same person, an account holding only vulnerability privileges.
        vulnOnlyToken = jwtService.generateToken(user.getUsername(),
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all")));
    }

    @Test
    void getAll_returnsEmptyListWhenNoNotifications() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getAll_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAll_returnsUserNotifications() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username)
                .title("Test notification")
                .message("You have a new assignment")
                .type("ASSESSOR_ASSIGNED")
                .link("/assessments/abc")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Test notification"))
                .andExpect(jsonPath("$.data[0].read").value(false));
    }

    @Test
    void getUnreadCount_returnsCorrectCount() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("T")
                .read(true).readAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    void markRead_updatesNotification() throws Exception {
        Notification n = notificationRepository.save(Notification.builder()
                .username(username).title("Test").message("Msg").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(patch("/api/v1/notifications/" + n.getId() + "/read")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
    }

    @Test
    void markAllRead_marksAllUnread() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        long unread = notificationRepository.countByUsernameAndReadFalse(username);
        assert unread == 0;
    }

    @Test
    void mentions_returnMentionsAndThreadRepliesOnly_newestFirst() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("You were mentioned in a comment")
                .message("Bob Smith mentioned you in a comment")
                .type("MENTION").link("/applications/app-1/edit?comment=c1")
                .targetType(MentionTargetType.APPLICATION).targetId("app-1")
                .targetName("Payments API").actorUsername("bob").actorName("Bob Smith")
                .excerpt("take a look at this")
                .read(false).createdAt(LocalDateTime.now().minusMinutes(5)).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("New comment on SQL Injection")
                .message("Bob Smith commented on SQL Injection")
                .type("COMMENT_ADDED").link("/vulnerabilities?vuln=v-1&comment=c2")
                .targetType(MentionTargetType.VULNERABILITY).targetId("v-1")
                .targetName("SQL Injection").actorUsername("bob").actorName("Bob Smith")
                .read(false).createdAt(LocalDateTime.now()).build());
        // An assignment is not part of this feed — it is not something someone said to you.
        notificationRepository.save(Notification.builder()
                .username(username).title("Assigned").message("M").type("ASSESSOR_ASSIGNED")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].type").value("COMMENT_ADDED"))
                .andExpect(jsonPath("$.data[0].targetType").value("VULNERABILITY"))
                .andExpect(jsonPath("$.data[0].targetName").value("SQL Injection"))
                .andExpect(jsonPath("$.data[1].type").value("MENTION"))
                .andExpect(jsonPath("$.data[1].targetType").value("APPLICATION"))
                .andExpect(jsonPath("$.data[1].actorName").value("Bob Smith"))
                .andExpect(jsonPath("$.data[1].excerpt").value("take a look at this"));
    }

    @Test
    void mentionsUnreadCount_countsOnlyUnreadMentionsAndThreadReplies() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("MENTION")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("COMMENT_ADDED")
                .read(true).readAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("C").message("M").type("ASSESSOR_ASSIGNED")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/v1/notifications/mentions/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    void deleteAllMentions_clearsTheFeedButLeavesOtherNotifications() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("MENTION")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("COMMENT_ADDED")
                .read(true).readAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
        // An assignment is not part of this feed, so clearing the feed must not take it.
        notificationRepository.save(Notification.builder()
                .username(username).title("C").message("M").type("ASSESSOR_ASSIGNED")
                .read(false).createdAt(LocalDateTime.now()).build());
        Notification other = notificationRepository.save(Notification.builder()
                .username("someone-else").title("D").message("M").type("MENTION")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(delete("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("ASSESSOR_ASSIGNED"));

        assert notificationRepository.findById(other.getId()).isPresent();
    }

    @Test
    void deleteAllMentions_canClearOneSectionOfTheDashboard() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("MENTION")
                .targetType(MentionTargetType.APPLICATION).targetId("app-1")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("COMMENT_ADDED")
                .targetType(MentionTargetType.VULNERABILITY).targetId("v-1")
                .read(false).createdAt(LocalDateTime.now()).build());
        // Pre-context row: no target, so it belongs to the "Other" section.
        notificationRepository.save(Notification.builder()
                .username(username).title("C").message("M").type("MENTION")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(delete("/api/v1/notifications/mentions")
                        .param("targetType", "APPLICATION")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(2));

        // NONE clears the untargeted rows only — a different request from clearing all.
        mockMvc.perform(delete("/api/v1/notifications/mentions")
                        .param("targetType", "NONE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetType").value("VULNERABILITY"));
    }

    @Test
    void deleteAllMentions_rejectsAnUnknownSection() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications/mentions")
                        .param("targetType", "PLANETS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteAllMentions_requiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications/mentions"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stream_opensWithBothUnreadCounts() throws Exception {
        // The sidebar's Mentions badge and the bell badge are both driven by this stream,
        // so a fresh subscription has to carry both numbers, not just the bell's.
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("MENTION")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("ASSESSOR_ASSIGNED")
                .read(false).createdAt(LocalDateTime.now()).build());

        MvcResult result = mockMvc.perform(get("/api/v1/notifications/stream")
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:unread_count").contains("data:2");
        assertThat(body).contains("event:mentions_unread_count").contains("data:1");
    }

    @Test
    void mentions_hideSectionsTheCallerHasNoPrivilegeOn() throws Exception {
        // Being @mentioned somewhere does not imply access to it: an assessment note or
        // an application thread must not leak into the feed of an account with no
        // privileges on that resource.
        notificationRepository.save(Notification.builder()
                .username(username).title("App").message("M").type("MENTION")
                .targetType(MentionTargetType.APPLICATION).targetId("app-1")
                .read(false).createdAt(LocalDateTime.now().minusMinutes(3)).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("Note").message("M").type("MENTION")
                .targetType(MentionTargetType.NOTEBOOK).targetId("node-1")
                .read(false).createdAt(LocalDateTime.now().minusMinutes(2)).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("Vuln").message("M").type("COMMENT_ADDED")
                .targetType(MentionTargetType.VULNERABILITY).targetId("v-1")
                .read(false).createdAt(LocalDateTime.now().minusMinutes(1)).build());
        // No target: the reader's own pre-context row, shown under "Other" and therefore
        // never filtered out — otherwise its unread count could never be cleared.
        notificationRepository.save(Notification.builder()
                .username(username).title("Legacy").message("M").type("MENTION")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + vulnOnlyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("Legacy"))
                .andExpect(jsonPath("$.data[1].title").value("Vuln"));

        // The badge counts what the page shows, so it can always be cleared.
        mockMvc.perform(get("/api/v1/notifications/mentions/unread-count")
                        .header("Authorization", "Bearer " + vulnOnlyToken))
                .andExpect(jsonPath("$.data").value(2));

        // The same rows are all there for an account privileged on every resource.
        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(4));
    }

    @Test
    void deleteAllMentions_leavesSectionsTheCallerCannotSee() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("Note").message("M").type("MENTION")
                .targetType(MentionTargetType.NOTEBOOK).targetId("node-1")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("Vuln").message("M").type("COMMENT_ADDED")
                .targetType(MentionTargetType.VULNERABILITY).targetId("v-1")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(delete("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + vulnOnlyToken))
                .andExpect(status().isOk());

        // "Delete all" clears what that reader can see — not rows they were never shown.
        mockMvc.perform(get("/api/v1/notifications/mentions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Note"));
    }

    @Test
    void stream_opensWithTheCallersScopedMentionCount() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("Note").message("M").type("MENTION")
                .targetType(MentionTargetType.NOTEBOOK).targetId("node-1")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("Vuln").message("M").type("COMMENT_ADDED")
                .targetType(MentionTargetType.VULNERABILITY).targetId("v-1")
                .read(false).createdAt(LocalDateTime.now()).build());

        MvcResult result = mockMvc.perform(get("/api/v1/notifications/stream")
                        .header("Authorization", "Bearer " + vulnOnlyToken))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Bell count is every notification; the Mentions badge is only the visible ones.
        assertThat(result.getResponse().getContentAsString())
                .contains("event:unread_count").contains("data:2")
                .contains("event:mentions_unread_count").contains("data:1");
    }

    @Test
    void mentions_requireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/mentions"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteAll_removesOnlyTheCurrentUsersNotifications() throws Exception {
        notificationRepository.save(Notification.builder()
                .username(username).title("A").message("M").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());
        notificationRepository.save(Notification.builder()
                .username(username).title("B").message("M").type("T")
                .read(true).readAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build());
        Notification other = notificationRepository.save(Notification.builder()
                .username("someone-else").title("C").message("M").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(delete("/api/v1/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assert notificationRepository.findByUsernameOrderByCreatedAtDesc(username).isEmpty();
        assert notificationRepository.findById(other.getId()).isPresent();
    }

    @Test
    void deleteAll_requiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_removesNotification() throws Exception {
        Notification n = notificationRepository.save(Notification.builder()
                .username(username).title("To delete").message("Msg").type("T")
                .read(false).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(delete("/api/v1/notifications/" + n.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assert notificationRepository.findById(n.getId()).isEmpty();
    }
}
