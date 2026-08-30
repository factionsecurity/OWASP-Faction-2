package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Application;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationCommentControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String jwtToken;
    private Application testApp;

    @BeforeEach
    void setUp() {
        applicationRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("SuperAdmin")
                .permissions(List.of("super_admin"))
                .build());

        User user = userRepository.save(User.builder()
                .username("app-comment-test-user")
                .firstName("Test")
                .lastName("User")
                .email("app-comment@test.com")
                .password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        jwtToken = jwtService.generateToken(
                user.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));

        testApp = applicationRepository.save(Application.builder()
                .name("Comment Test App")
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Test
    void addComment_returnsForbiddenWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/applications/{id}/comments", testApp.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Hello\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void addComment_returnsCommentsList() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("content", "This is a **test** comment"));

        mockMvc.perform(post("/api/v1/applications/{id}/comments", testApp.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].content").value("This is a **test** comment"))
                .andExpect(jsonPath("$.data[0].authorId").value("app-comment-test-user"))
                .andExpect(jsonPath("$.data[0].authorName").value("Test User"))
                .andExpect(jsonPath("$.data[0].id").isNotEmpty());
    }

    @Test
    void addComment_returns400ForBlankContent() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("content", ""));

        mockMvc.perform(post("/api/v1/applications/{id}/comments", testApp.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addComment_returns404ForMissingApplication() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("content", "Hello"));

        mockMvc.perform(post("/api/v1/applications/{id}/comments", "non-existent-app-id")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteComment_removesCommentAndReturnsList() throws Exception {
        String addBody = objectMapper.writeValueAsString(Map.of("content", "To be deleted"));
        String addResult = mockMvc.perform(post("/api/v1/applications/{id}/comments", testApp.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String commentId = objectMapper.readTree(addResult).path("data").get(0).path("id").asText();

        mockMvc.perform(delete("/api/v1/applications/{id}/comments/{cid}", testApp.getId(), commentId)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void addComment_appearsInApplicationGet() throws Exception {
        mockMvc.perform(post("/api/v1/applications/{id}/comments", testApp.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Visible in GET\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/applications/{id}", testApp.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments", hasSize(1)))
                .andExpect(jsonPath("$.data.comments[0].content").value("Visible in GET"));
    }
}
