package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.dto.LoginRequest;
import com.faction.clientportal.dto.LoginResponse;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")

class AuthControllerTest extends TestContainersConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Role testRole;

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

        User testUser = User.builder()
                .username("testuser")
                .password(passwordEncoder.encode("testpass"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(testRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
        userRepository.save(testUser);
    }

    @Test
    void login_WithValidCredentials_ReturnsJwtToken() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .username("testuser")
                .password("testpass")
                .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.authorities").isArray())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.expiresIn").exists())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);

        assertThat(loginResponse.getToken()).isNotEmpty();
        assertThat(loginResponse.getAuthorities()).containsExactlyInAnyOrder("test:read", "test:write");
        assertThat(loginResponse.getRoles()).containsExactly("TestRole");
    }

    @Test
    void me_ReturnsRoleNames() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .username("testuser")
                .password("testpass")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse loginResponse = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), LoginResponse.class);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + loginResponse.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.authorities").isArray())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[0]").value("TestRole"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void login_WithInvalidCredentials_Returns401() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .username("testuser")
                .password("wrongpass")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void login_WithDifferentCaseUsername_SignsInAsTheStoredUsername() throws Exception {
        String token = loginAndReturnToken("TestUser", "testpass", "testuser");

        // The token must carry the stored username, or every later lookup by it would miss.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    void login_WhenUsernamesDifferOnlyInCase_TheExactOneWins() throws Exception {
        userRepository.save(caseVariant("TestUser", "otherpass"));

        String token = loginAndReturnToken("TestUser", "otherpass", "TestUser");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.username").value("TestUser"));
    }

    @Test
    void login_WhenSeveralUsernamesMatchOnlyIgnoringCase_Returns401() throws Exception {
        userRepository.save(caseVariant("TestUser", "testpass"));

        // Neither stored name is "TESTUSER"; signing in as either would be a guess.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginRequest.builder()
                                .username("TESTUSER").password("testpass").build())))
                .andExpect(status().isUnauthorized());
    }

    private User caseVariant(String username, String password) {
        return User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(testRole.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build();
    }

    private String loginAndReturnToken(String username, String password, String expectedUsername)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginRequest.builder()
                                .username(username).password(password).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(expectedUsername))
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class)
                .getToken();
    }

    @Test
    void login_WithNonExistentUser_Returns401() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .username("nonexistent")
                .password("testpass")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_WithMissingUsername_Returns400() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .password("testpass")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_WithMissingPassword_Returns400() throws Exception {
        LoginRequest loginRequest = LoginRequest.builder()
                .username("testuser")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest());
    }
}
