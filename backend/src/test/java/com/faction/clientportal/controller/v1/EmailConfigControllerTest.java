package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.EmailConfigRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailConfigControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmailConfigRepository emailConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        emailConfigRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = roleRepository.save(Role.builder()
                .name("SuperAdmin")
                .permissions(List.of("super_admin"))
                .build());

        Role basicRole = roleRepository.save(Role.builder()
                .name("Basic")
                .permissions(List.of("vulnerabilities:read:all"))
                .build());

        User admin = userRepository.save(User.builder()
                .username("email-config-admin")
                .firstName("Admin")
                .lastName("User")
                .email("admin@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(adminRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        User basic = userRepository.save(User.builder()
                .username("email-config-basic")
                .firstName("Basic")
                .lastName("User")
                .email("basic@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(basicRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        adminToken = jwtService.generateToken(admin.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
        userToken = jwtService.generateToken(basic.getUsername(),
                List.of(new SimpleGrantedAuthority("vulnerabilities:read:all")));
    }

    @Test
    void getConfig_returnsDefaultConfig() throws Exception {
        var result = mockMvc.perform(get("/api/v1/admin/email-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"enabled\":false");
        assertThat(body).contains("\"port\":587");
        assertThat(body).contains("\"security\":\"STARTTLS\"");
    }

    @Test
    void getConfig_requiresAdminAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/email-config")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateConfig_persistsSettings() throws Exception {
        Map<String, Object> request = Map.of(
                "enabled", true,
                "provider", "GMAIL",
                "host", "smtp.gmail.com",
                "port", 587,
                "username", "user@gmail.com",
                "fromName", "Test Sender",
                "fromEmail", "user@gmail.com",
                "security", "STARTTLS",
                "authEnabled", true
        );

        mockMvc.perform(put("/api/v1/admin/email-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.host").value("smtp.gmail.com"))
                .andExpect(jsonPath("$.data.username").value("user@gmail.com"))
                .andExpect(jsonPath("$.data.security").value("STARTTLS"));
    }

    @Test
    void updateConfig_masksStoredPassword() throws Exception {
        Map<String, Object> request = Map.of(
                "host", "smtp.gmail.com",
                "username", "user@gmail.com",
                "password", "secret-password"
        );

        var result = mockMvc.perform(put("/api/v1/admin/email-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").value("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"))
                .andReturn();

        // Response should not contain the plaintext password
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("secret-password");
    }

    @Test
    void updateConfig_preservesPasswordWhenMaskSent() throws Exception {
        // First save a password
        Map<String, Object> initial = Map.of(
                "host", "smtp.gmail.com",
                "password", "original-password"
        );
        mockMvc.perform(put("/api/v1/admin/email-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initial)))
                .andExpect(status().isOk());

        // Now update with masked password — should preserve the original
        Map<String, Object> update = Map.of(
                "host", "smtp.gmail.com",
                "password", "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
        );
        mockMvc.perform(put("/api/v1/admin/email-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").value("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"));
    }

    @Test
    void testConnection_returnsErrorWhenNotConfigured() throws Exception {
        Map<String, Object> request = Map.of("to", "test@example.com");

        mockMvc.perform(post("/api/v1/admin/email-config/test")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("SMTP host is not configured."));
    }

    @Test
    void testConnection_requiresAdminAuth() throws Exception {
        Map<String, Object> request = Map.of("to", "test@example.com");

        mockMvc.perform(post("/api/v1/admin/email-config/test")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
