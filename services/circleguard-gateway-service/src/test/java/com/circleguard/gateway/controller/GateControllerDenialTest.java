package com.circleguard.gateway.controller;

import com.circleguard.gateway.service.QrValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Additional unit tests for GateController — access denial scenarios.
 * Covers: CONTAGIED/POTENTIAL status, invalid token, null token, message content.
 */
@WebMvcTest(GateController.class)
class GateControllerDenialTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QrValidationService validationService;

    // ── Test 1: Access denied for CONTAGIED user ──────────────────────────────

    @Test
    @DisplayName("Gate validate: CONTAGIED user gets valid=false, status=RED")
    void validate_ContagiedUser_DeniesAccessWithRedStatus() throws Exception {
        // Arrange
        QrValidationService.ValidationResult deniedResult =
                new QrValidationService.ValidationResult(false, "RED", "Access Denied: Health Risk Detected");

        when(validationService.validateToken("contagied-token")).thenReturn(deniedResult);

        // Act & Assert
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"contagied-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("RED"))
                .andExpect(jsonPath("$.message").value("Access Denied: Health Risk Detected"));
    }

    // ── Test 2: Access denied for POTENTIAL status ────────────────────────────

    @Test
    @DisplayName("Gate validate: POTENTIAL user gets valid=false, status=RED")
    void validate_PotentialUser_DeniesAccessWithRedStatus() throws Exception {
        // Arrange
        QrValidationService.ValidationResult deniedResult =
                new QrValidationService.ValidationResult(false, "RED", "Access Denied: Health Risk Detected");

        when(validationService.validateToken("potential-token")).thenReturn(deniedResult);

        // Act & Assert
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"potential-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("RED"));
    }

    // ── Test 3: Invalid / expired token ──────────────────────────────────────

    @Test
    @DisplayName("Gate validate: invalid or expired token returns valid=false and RED status")
    void validate_InvalidToken_ReturnsFalseWithMessage() throws Exception {
        // Arrange
        QrValidationService.ValidationResult invalidResult =
                new QrValidationService.ValidationResult(false, "RED", "Invalid or Expired Token");

        when(validationService.validateToken("bad-token")).thenReturn(invalidResult);

        // Act & Assert
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"bad-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or Expired Token"));
    }

    // ── Test 4: No token in request body (null) ───────────────────────────────

    @Test
    @DisplayName("Gate validate: absent token key causes service to be called with null")
    void validate_NullToken_ServiceReceivesNull() throws Exception {
        // Arrange — service handles null gracefully (returns invalid result)
        QrValidationService.ValidationResult nullResult =
                new QrValidationService.ValidationResult(false, "RED", "Invalid or Expired Token");

        when(validationService.validateToken(isNull())).thenReturn(nullResult);

        // Act & Assert — body with no token key
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        verify(validationService, times(1)).validateToken(null);
    }

    // ── Test 5: Successful access response contains welcome message ───────────

    @Test
    @DisplayName("Gate validate: allowed user receives valid=true, status=GREEN, welcome message")
    void validate_AllowedUser_ReturnsGreenWithWelcomeMessage() throws Exception {
        // Arrange
        QrValidationService.ValidationResult allowed =
                new QrValidationService.ValidationResult(true, "GREEN", "Welcome to Campus");

        when(validationService.validateToken("valid-clean-token")).thenReturn(allowed);

        // Act & Assert
        mockMvc.perform(post("/api/v1/gate/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"valid-clean-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("GREEN"))
                .andExpect(jsonPath("$.message").value("Welcome to Campus"));
    }
}
