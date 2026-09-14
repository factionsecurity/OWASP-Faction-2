package com.faction.clientportal.controller.v1;

import com.faction.clientportal.config.TestContainersConfig;
import com.faction.clientportal.model.Permission;
import com.faction.clientportal.service.JwtService;
import com.faction.clientportal.service.SlaRecalculationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin repair path: re-run the stored-due-date recalculation on demand. The service is mocked, so
 * no real background run races other tests' findings; the test proves the endpoint's gate and that it
 * starts the run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssessmentWorkflowConfigRecalculateSlaTest extends TestContainersConfig {

    private static final String URL = "/api/v1/config/assessment-workflow/recalculate-sla";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private SlaRecalculationService slaRecalculationService;

    private String token(String authority) {
        return jwtService.generateToken("recalc-user", List.of(new SimpleGrantedAuthority(authority)));
    }

    @Test
    void aUserWithConfigWriteStartsTheRecalculation() throws Exception {
        mockMvc.perform(post(URL).header("Authorization", "Bearer " + token(Permission.CONFIG_WRITE.getPermission())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("SLA recalculation started"));

        verify(slaRecalculationService, timeout(5000)).recalculateInBackground();
    }

    @Test
    void withoutConfigWriteItIsForbidden() throws Exception {
        mockMvc.perform(post(URL).header("Authorization",
                        "Bearer " + token(Permission.VULNERABILITIES_READ_ALL.getPermission())))
                .andExpect(status().isForbidden());

        verify(slaRecalculationService, never()).recalculateInBackground();
    }

    @Test
    void withoutATokenItIsForbidden() throws Exception {
        mockMvc.perform(post(URL)).andExpect(status().isForbidden());

        verify(slaRecalculationService, never()).recalculateInBackground();
    }
}
