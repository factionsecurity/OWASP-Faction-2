package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.ApplicationIdConfigRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationIdConfigControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationIdConfigRepository applicationIdConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        // Only reset the config table — deleting all users would destroy the
        // bootstrapped admin account that other test classes depend on.
        applicationIdConfigRepository.deleteAll();

        Role adminRole = roleRepository.findByName("AppIdConfigSuperAdmin")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name("AppIdConfigSuperAdmin")
                        .permissions(List.of("super_admin"))
                        .build()));

        Role basicRole = roleRepository.findByName("AppIdConfigBasic")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name("AppIdConfigBasic")
                        .permissions(List.of("applications:read:all"))
                        .build()));

        User admin = userRepository.findByUsername("appid-config-admin")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("appid-config-admin")
                        .firstName("Admin")
                        .lastName("User")
                        .email("appid-admin@test.com")
                        .password(passwordEncoder.encode("password"))
                        .loginOption(LoginOption.NATIVE)
                        .roleIds(List.of(adminRole.getId()))
                        .isInternal(true)
                        .createdAt(LocalDateTime.now())
                        .failedLoginAttempts(0)
                        .build()));

        User basic = userRepository.findByUsername("appid-config-basic")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("appid-config-basic")
                        .firstName("Basic")
                        .lastName("User")
                        .email("appid-basic@test.com")
                        .password(passwordEncoder.encode("password"))
                        .loginOption(LoginOption.NATIVE)
                        .roleIds(List.of(basicRole.getId()))
                        .isInternal(true)
                        .createdAt(LocalDateTime.now())
                        .failedLoginAttempts(0)
                        .build()));

        adminToken = jwtService.generateToken(admin.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
        userToken = jwtService.generateToken(basic.getUsername(),
                List.of(new SimpleGrantedAuthority("applications:read:all")));
    }

    @Test
    void getConfig_seedsAndReturnsDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.prefix").value("ASMT"))
                .andExpect(jsonPath("$.data.nextNumber").value(1))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void endpoints_requireSuperAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prefix", "HACK"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/application-id-config/next")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/application-id-config/preview")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateConfig_persistsSettings() throws Exception {
        Map<String, Object> request = Map.of(
                "prefix", "WEB",
                "nextNumber", 100,
                "enabled", false
        );

        mockMvc.perform(put("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prefix").value("WEB"))
                .andExpect(jsonPath("$.data.nextNumber").value(100))
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(get("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prefix").value("WEB"))
                .andExpect(jsonPath("$.data.nextNumber").value(100))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void next_generatesSequentialIds() throws Exception {
        mockMvc.perform(put("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prefix", "SEQ", "nextNumber", 1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/application-id-config/next")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("SEQ-1"));

        mockMvc.perform(get("/api/v1/admin/application-id-config/next")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("SEQ-2"));

        mockMvc.perform(get("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextNumber").value(3));
    }

    @Test
    void preview_returnsUpcomingIdsWithoutConsuming() throws Exception {
        mockMvc.perform(put("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prefix", "PRE", "nextNumber", 5))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/application-id-config/preview")
                        .param("count", "3")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("PRE-5"))
                .andExpect(jsonPath("$.data[1]").value("PRE-6"))
                .andExpect(jsonPath("$.data[2]").value("PRE-7"));

        mockMvc.perform(get("/api/v1/admin/application-id-config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextNumber").value(5));
    }
}
