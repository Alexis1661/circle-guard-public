package com.circleguard.promotion.controller;

import com.circleguard.promotion.service.HealthStatusService;
import com.circleguard.promotion.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthStatusController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = {"jwt.secret=my-super-secret-test-key-must-be-at-least-32-bytes-long"})
class HealthStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthStatusService statusService;

    @Test
    // roles = "HEALTH_CENTER" adds ROLE_HEALTH_CENTER authority,
    // which satisfies @PreAuthorize("hasRole('HEALTH_CENTER')")
    @WithMockUser(roles = "HEALTH_CENTER")
    void confirmPositive_WithPermission_CallsUpdateStatus() throws Exception {
        String json = "{\"anonymousId\": \"user-1\"}";

        mockMvc.perform(post("/api/v1/health/confirmed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        verify(statusService).updateStatus("user-1", "CONFIRMED");
    }

    @Test
    @WithMockUser(roles = "HEALTH_CENTER")
    void resolve_WithPermission_CallsResolveStatus() throws Exception {
        String json = "{\"anonymousId\": \"user-1\"}";

        mockMvc.perform(post("/api/v1/health/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        verify(statusService).resolveStatus("user-1", false);
    }

    @Test
    @WithMockUser(authorities = "STUDENT")
    void resolve_WithoutPermission_Returns403() throws Exception {
        String json = "{\"anonymousId\": \"user-1\"}";

        mockMvc.perform(post("/api/v1/health/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolve_Unauthenticated_Returns403() throws Exception {
        String json = "{\"anonymousId\": \"user-1\"}";

        mockMvc.perform(post("/api/v1/health/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }
}
