package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.RegionConfigRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.RegionConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Region config had no test of any kind, and its PUT replaces the whole list in one write —
 * the same shape of quiet destruction as the image GC. Every application in the system carries a
 * region, so an unguarded overwrite here strands them all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionConfigControllerTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private RegionConfigRepository regionConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;

    private String superAdminToken;
    private String ordinaryToken;

    @BeforeEach
    void setUp() {
        regionConfigRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        superAdminToken = tokenFor("root", "super_admin");
        ordinaryToken = tokenFor("staff", "assessments:read:all");
    }

    @Test
    void regionsFallBackToTheBuiltInListBeforeAnyoneConfiguresThem() throws Exception {
        mockMvc.perform(get("/api/v1/config/regions").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(RegionConfigService.DEFAULT_REGIONS.size()))
                .andExpect(jsonPath("$.data[0]").value("Global"));
    }

    @Test
    void aSuperAdminReplacesTheList() throws Exception {
        mockMvc.perform(put("/api/v1/config/regions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"UK\",\"Ireland\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/config/regions").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data[0]").value("UK"))
                .andExpect(jsonPath("$.data[1]").value("Ireland"));
    }

    @Test
    void updatingTwiceReplacesRatherThanAccumulates() throws Exception {
        putRegions("[\"UK\",\"Ireland\"]");
        putRegions("[\"APAC\"]");

        // One singleton row, replaced — not a growing list and not a second row.
        mockMvc.perform(get("/api/v1/config/regions").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value("APAC"));
        assertThat(regionConfigRepository.count()).isEqualTo(1);
    }

    @Test
    void anOrdinaryUserCannotReplaceTheList() throws Exception {
        putRegions("[\"UK\"]");

        mockMvc.perform(put("/api/v1/config/regions")
                        .header("Authorization", "Bearer " + ordinaryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"Nowhere\"]"))
                .andExpect(status().isForbidden());

        // The refusal has to be a refusal, not a partial write.
        mockMvc.perform(get("/api/v1/config/regions").header("Authorization", "Bearer " + superAdminToken))
                .andExpect(jsonPath("$.data[0]").value("UK"));
    }

    @Test
    void readingRegionsNeedsOnlyALogin() throws Exception {
        // Every application form shows this list, so it cannot be super-admin gated like the write.
        mockMvc.perform(get("/api/v1/config/regions").header("Authorization", "Bearer " + ordinaryToken))
                .andExpect(status().isOk());
    }

    @Test
    void regionsRequireAuthentication() throws Exception {
        // 403 rather than 401 for an anonymous caller is this application's convention throughout
        // — see the other controller tests. Asserted so a change to it is a deliberate one.
        mockMvc.perform(get("/api/v1/config/regions")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/config/regions")
                        .contentType(MediaType.APPLICATION_JSON).content("[\"X\"]"))
                .andExpect(status().isForbidden());
    }

    private void putRegions(String json) throws Exception {
        mockMvc.perform(put("/api/v1/config/regions")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private String tokenFor(String username, String authority) {
        Role role = roleRepository.save(Role.builder()
                .name(username + "-role").permissions(List.of(authority)).build());
        User user = userRepository.save(User.builder()
                .username(username).firstName("T").lastName("U").email(username + "@test.com")
                .password("x").loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build());
        return jwtService.generateToken(user.getUsername(),
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
