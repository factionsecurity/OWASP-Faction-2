package com.faction.clientportal.controller.v1;

import com.faction.clientportal.edition.EnterpriseOnly;
import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.AiTokenUsageDay;
import com.faction.clientportal.model.LoginOption;
import com.faction.clientportal.model.Role;
import com.faction.clientportal.model.User;
import com.faction.clientportal.repository.AiTokenUsageDayRepository;
import com.faction.clientportal.repository.RoleRepository;
import com.faction.clientportal.repository.UserRepository;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.ai.AiTokenUsage;
import com.faction.clientportal.service.ai.AiTokenUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiTokenUsageTest extends TestContainersConfig {

    @Autowired private MockMvc mockMvc;
    @Autowired private AiTokenUsageService aiTokenUsageService;
    @Autowired private AiTokenUsageDayRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String noAccessToken;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = roleRepository.save(Role.builder()
                .name("SuperAdmin").permissions(List.of("super_admin")).build());
        Role limitedRole = roleRepository.save(Role.builder()
                .name("Pentester").permissions(List.of("assessments:read:all")).build());

        User admin = userRepository.save(user("usage-admin", adminRole));
        User limited = userRepository.save(user("usage-limited", limitedRole));

        adminToken = jwtService.generateToken(admin.getUsername(),
                List.of(new SimpleGrantedAuthority("super_admin")));
        noAccessToken = jwtService.generateToken(limited.getUsername(),
                List.of(new SimpleGrantedAuthority("assessments:read:all")));
    }

    private User user(String username, Role role) {
        return User.builder()
                .username(username).firstName("Test").lastName("User")
                .email(username + "@test.com").password(passwordEncoder.encode("password"))
                .loginOption(LoginOption.NATIVE).roleIds(List.of(role.getId()))
                .isInternal(true).createdAt(LocalDateTime.now()).failedLoginAttempts(0).build();
    }

    private void seed(LocalDate date, String username, String model, long input, long output) {
        repository.save(AiTokenUsageDay.builder()
                .usageDate(date).username(username).providerName("Local").model(model)
                .inputTokens(input).outputTokens(output).requestCount(1).build());
    }

    // ── Ledger writes ─────────────────────────────────────────────────────────

    @Test
    void record_accumulatesRepeatCallsIntoOneRowPerDay() {
        aiTokenUsageService.record("alice", "OpenAI", "gpt-4o", new AiTokenUsage(1000, 200));
        aiTokenUsageService.record("alice", "OpenAI", "gpt-4o", new AiTokenUsage(500, 50));

        List<AiTokenUsageDay> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getInputTokens()).isEqualTo(1500);
        assertThat(rows.get(0).getOutputTokens()).isEqualTo(250);
        assertThat(rows.get(0).getRequestCount()).isEqualTo(2);
        assertThat(rows.get(0).getUsageDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void record_keepsUserProviderAndModelOnSeparateRows() {
        aiTokenUsageService.record("alice", "OpenAI", "gpt-4o", new AiTokenUsage(100, 10));
        aiTokenUsageService.record("bob", "OpenAI", "gpt-4o", new AiTokenUsage(100, 10));
        aiTokenUsageService.record("alice", "Anthropic", "claude", new AiTokenUsage(100, 10));

        assertThat(repository.findAll()).hasSize(3);
        // …but the chart sees one number per day, summed across all of them.
        List<?> totals = aiTokenUsageService.dailyTotals(LocalDate.now(), LocalDate.now());
        assertThat(totals).hasSize(1);
    }

    @Test
    void record_missingAttributionStillLandsOnOneRowRatherThanSplitting() {
        aiTokenUsageService.record(null, null, null, new AiTokenUsage(100, 10));
        aiTokenUsageService.record(null, null, null, new AiTokenUsage(100, 10));

        List<AiTokenUsageDay> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUsername()).isEqualTo("unknown");
        assertThat(rows.get(0).getRequestCount()).isEqualTo(2);
    }

    @Test
    void record_skipsCallsThatReportedNoTokens() {
        aiTokenUsageService.record("alice", "OpenAI", "gpt-4o", AiTokenUsage.NONE);
        aiTokenUsageService.record("alice", "OpenAI", "gpt-4o", null);

        assertThat(repository.findAll()).isEmpty();
    }

    // ── Daily totals ──────────────────────────────────────────────────────────

    @Test
    void dailyTotals_sumsEveryUserAndModelPerDayAndHonoursTheRange() {
        LocalDate today = LocalDate.now();
        seed(today, "alice", "gpt-4o", 1000, 100);
        seed(today, "bob", "claude", 2000, 300);
        seed(today.minusDays(1), "alice", "gpt-4o", 500, 50);
        seed(today.minusDays(40), "alice", "gpt-4o", 999_999, 999_999);

        var totals = aiTokenUsageService.dailyTotals(today.minusDays(5), today);

        assertThat(totals).hasSize(2);
        // Oldest first, so the chart can plot straight down the list.
        assertThat(totals.get(0).getDate()).isEqualTo(today.minusDays(1));
        assertThat(totals.get(0).getTotalTokens()).isEqualTo(550);
        assertThat(totals.get(1).getDate()).isEqualTo(today);
        assertThat(totals.get(1).getInputTokens()).isEqualTo(3000);
        assertThat(totals.get(1).getOutputTokens()).isEqualTo(400);
        assertThat(totals.get(1).getTotalTokens()).isEqualTo(3400);
        assertThat(totals.get(1).getRequests()).isEqualTo(2);
    }

    // ── Endpoint ──────────────────────────────────────────────────────────────

    @Test

    @EnterpriseOnly
    void usageEndpoint_returnsDailyTotalsNewestLast() throws Exception {
        LocalDate today = LocalDate.now();
        seed(today, "alice", "gpt-4o", 1000, 200);

        mockMvc.perform(get("/api/v1/admin/ai-config/usage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].date").value(today.toString()))
                .andExpect(jsonPath("$.data[0].totalTokens").value(1200))
                .andExpect(jsonPath("$.data[0].requests").value(1));
    }

    @Test

    @EnterpriseOnly
    void usageEndpoint_defaultRangeCoversLastMonthThroughToday() throws Exception {
        LocalDate startOfLastMonth = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        seed(startOfLastMonth, "alice", "gpt-4o", 10, 5);
        seed(startOfLastMonth.minusDays(1), "alice", "gpt-4o", 777, 777);

        mockMvc.perform(get("/api/v1/admin/ai-config/usage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                // The day before the window is excluded, so the chart never shows a stray month.
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].date").value(startOfLastMonth.toString()));
    }

    @Test

    @EnterpriseOnly
    void usageEndpoint_honoursExplicitFromAndTo() throws Exception {
        LocalDate today = LocalDate.now();
        seed(today, "alice", "gpt-4o", 100, 10);
        seed(today.minusDays(3), "alice", "gpt-4o", 200, 20);

        mockMvc.perform(get("/api/v1/admin/ai-config/usage")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].totalTokens").value(110));
    }

    @Test

    @EnterpriseOnly
    void usageEndpoint_forbiddenWithoutAiConfigPermission() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ai-config/usage")
                        .header("Authorization", "Bearer " + noAccessToken))
                .andExpect(status().isForbidden());
    }
}
