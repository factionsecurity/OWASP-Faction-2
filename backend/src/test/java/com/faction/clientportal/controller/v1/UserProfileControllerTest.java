package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.util.StoredObjects;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockBean private StorageService storageService;

    private String jwtToken;
    private User testUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role role = roleRepository.save(Role.builder()
                .name("Pentester")
                .permissions(List.of("assessments:read:assigned"))
                .build());

        testUser = userRepository.save(User.builder()
                .username("profile-user")
                .firstName("Profile")
                .lastName("Tester")
                .email("profile@test.com")
                .password(passwordEncoder.encode("oldPassword1"))
                .loginOption(LoginOption.NATIVE)
                .roleIds(List.of(role.getId()))
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        jwtToken = jwtService.generateToken(
                testUser.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:read:assigned")));
    }

    // ── GET /users/me ─────────────────────────────────────────────────────────

    @Test
    void me_returnsCurrentUserProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("profile-user"))
                .andExpect(jsonPath("$.data.email").value("profile@test.com"))
                .andExpect(jsonPath("$.data.profileImageId").isEmpty());
    }

    @Test
    void me_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isForbidden());
    }

    // ── Change password ───────────────────────────────────────────────────────

    @Test
    void changePassword_updatesPasswordWhenCurrentMatches() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "oldPassword1",
                                "newPassword", "newPassword1"))))
                .andExpect(status().isOk());

        User updated = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("newPassword1", updated.getPassword())).isTrue();
    }

    @Test
    void changePassword_rejectsWrongCurrentPassword() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "wrongPassword",
                                "newPassword", "newPassword1"))))
                .andExpect(status().isBadRequest());

        User unchanged = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("oldPassword1", unchanged.getPassword())).isTrue();
    }

    @Test
    void changePassword_rejectsShortNewPassword() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "oldPassword1",
                                "newPassword", "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_rejectsSsoManagedAccount() throws Exception {
        testUser.setLoginOption(LoginOption.SAML2);
        userRepository.save(testUser);

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "oldPassword1",
                                "newPassword", "newPassword1"))))
                .andExpect(status().isBadRequest());
    }

    // ── Profile image ─────────────────────────────────────────────────────────

    @Test
    void uploadProfileImage_storesImageAndSetsId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/users/me/profile-image").file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageId").isNotEmpty());

        User updated = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(updated.getProfileImageId()).isNotNull();
        assertThat(updated.getProfileImageKey())
                .isEqualTo("profile-images/" + updated.getProfileImageId());
        verify(storageService).uploadBytes(
                eq(updated.getProfileImageKey()), any(byte[].class), anyString());
    }

    @Test
    void uploadProfileImage_rejectsNonImageContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/users/me/profile-image").file(file)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeProfileImage_clearsImageFields() throws Exception {
        testUser.setProfileImageId("img-1");
        testUser.setProfileImageKey("profile-images/img-1");
        userRepository.save(testUser);

        mockMvc.perform(delete("/api/v1/users/me/profile-image")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        User updated = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(updated.getProfileImageId()).isNull();
        assertThat(updated.getProfileImageKey()).isNull();
    }

    @Test
    void avatarMap_isKeyedByIdAndUsernameWithEmailSeed() throws Exception {
        testUser.setProfileImageId("img-1");
        testUser.setProfileImageKey("profile-images/img-1");
        userRepository.save(testUser);

        userRepository.save(User.builder()
                .username("no-image-user")
                .email("noimage@test.com")
                .password(passwordEncoder.encode("password1"))
                .loginOption(LoginOption.NATIVE)
                .isInternal(true)
                .createdAt(LocalDateTime.now())
                .failedLoginAttempts(0)
                .build());

        mockMvc.perform(get("/api/v1/users/avatars")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                // Keyed by both user id and username (comments store usernames);
                // the seed is the user's email so identicons match everywhere
                .andExpect(jsonPath("$.data." + testUser.getId() + ".profileImageId").value("img-1"))
                .andExpect(jsonPath("$.data.profile-user.profileImageId").value("img-1"))
                .andExpect(jsonPath("$.data.profile-user.seed").value("profile@test.com"))
                .andExpect(jsonPath("$.data.no-image-user.seed").value("noimage@test.com"))
                .andExpect(jsonPath("$.data.no-image-user.profileImageId").isEmpty());
    }

    @Test
    void serveProfileImage_streamsBytes() throws Exception {
        testUser.setProfileImageId("img-1");
        testUser.setProfileImageKey("profile-images/img-1");
        userRepository.save(testUser);
        when(storageService.openStream("profile-images/img-1"))
                .thenReturn(StoredObjects.of("png-bytes".getBytes(), "image/png"));

        mockMvc.perform(get("/api/v1/profile-images/img-1")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(content().string("png-bytes"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void serveProfileImage_rejectsAnonymousCallers() throws Exception {
        testUser.setProfileImageId("img-1");
        testUser.setProfileImageKey("profile-images/img-1");
        userRepository.save(testUser);

        // Was previously a permitAll endpoint guarded only by an unguessable id.
        mockMvc.perform(get("/api/v1/profile-images/img-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void serveProfileImage_acceptsTheMediaCookieForImgTags() throws Exception {
        testUser.setProfileImageId("img-1");
        testUser.setProfileImageKey("profile-images/img-1");
        userRepository.save(testUser);
        when(storageService.openStream("profile-images/img-1"))
                .thenReturn(StoredObjects.of("png-bytes".getBytes(), "image/png"));

        // An <img> tag cannot set an Authorization header, so the cookie stands in.
        mockMvc.perform(get("/api/v1/profile-images/img-1")
                        .cookie(new jakarta.servlet.http.Cookie("media_access", jwtToken)))
                .andExpect(status().isOk())
                .andExpect(content().string("png-bytes"));
    }

    @Test
    void serveProfileImage_returns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/profile-images/does-not-exist")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }
}
