package com.circleguard.promotion.controller;

import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.security.SecurityConfig;
import com.circleguard.promotion.service.AutoCircleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for EncounterController.
 * Covers encounter reporting, access control for restricted operations.
 */
@WebMvcTest(EncounterController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = {"jwt.secret=my-super-secret-test-key-must-be-at-least-32-bytes-long"})
class EncounterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserNodeRepository userRepository;

    @MockBean
    private AutoCircleService autoCircleService;

    // ── Test 1: Report encounter — happy path ─────────────────────────────────

    @Test
    @DisplayName("Report encounter: valid sourceId, targetId, locationId records the encounter")
    void reportEncounter_WithValidPayload_RecordsEncounterAndEvaluatesAutoCircle() throws Exception {
        // Arrange
        doNothing().when(userRepository).recordEncounter(anyString(), anyString(), anyLong(), anyString());
        doNothing().when(autoCircleService).evaluateEncounter(anyString(), anyString());

        // Act & Assert
        mockMvc.perform(post("/api/v1/encounters/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"sourceId\": \"user-A\", " +
                                "\"targetId\": \"user-B\", " +
                                "\"locationId\": \"lab-101\"" +
                                "}"))
                .andExpect(status().isOk());

        verify(userRepository, times(1))
                .recordEncounter(eq("user-A"), eq("user-B"), anyLong(), eq("lab-101"));
        verify(autoCircleService, times(1)).evaluateEncounter("user-A", "user-B");
    }

    // ── Test 2: Report encounter without locationId uses default ──────────────

    @Test
    @DisplayName("Report encounter: missing locationId defaults to 'mobile_ble'")
    void reportEncounter_MissingLocationId_DefaultsToMobileBle() throws Exception {
        // Arrange
        doNothing().when(userRepository).recordEncounter(anyString(), anyString(), anyLong(), anyString());

        // Act
        mockMvc.perform(post("/api/v1/encounters/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\": \"user-X\", \"targetId\": \"user-Y\"}"))
                .andExpect(status().isOk());

        // Assert — default location is "mobile_ble"
        verify(userRepository).recordEncounter(eq("user-X"), eq("user-Y"), anyLong(), eq("mobile_ble"));
    }

    // ── Test 3: Toggle encounter validity — without HEALTH_CENTER → 403 ───────

    @Test
    @DisplayName("Toggle encounter validity: request without HEALTH_CENTER role returns 403")
    @WithMockUser(roles = "STUDENT")
    void toggleEncounterValidity_WithoutHealthCenterRole_Returns403() throws Exception {
        // Arrange — STUDENT role, not HEALTH_CENTER

        // Act & Assert
        mockMvc.perform(patch("/api/v1/encounters/{id}/validity", 1L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userRepository);
    }

    // ── Test 4: Force fence encounter — without HEALTH_CENTER → 403 ──────────

    @Test
    @DisplayName("Force fence encounter: request without HEALTH_CENTER role returns 403")
    @WithMockUser(roles = "STUDENT")
    void forceFenceEncounter_WithoutHealthCenterRole_Returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/v1/encounters/{id}/force-fence", 1L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userRepository);
    }

    // ── Test 5: AutoCircleService is always called after encounter recording ──

    @Test
    @DisplayName("Report encounter: AutoCircleService.evaluateEncounter called for every valid report")
    void reportEncounter_AlwaysTriggersAutoCircleEvaluation() throws Exception {
        // Arrange
        doNothing().when(userRepository).recordEncounter(any(), any(), anyLong(), any());

        // Act
        mockMvc.perform(post("/api/v1/encounters/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceId\": \"alice\", \"targetId\": \"bob\"}"))
                .andExpect(status().isOk());

        // Assert — auto circle evaluation is triggered
        verify(autoCircleService, times(1)).evaluateEncounter("alice", "bob");
    }
}
