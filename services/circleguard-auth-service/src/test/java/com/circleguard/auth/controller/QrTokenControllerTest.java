package com.circleguard.auth.controller;

import com.circleguard.auth.security.SecurityConfig;
import com.circleguard.auth.service.CustomUserDetailsService;
import com.circleguard.auth.service.QrTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for QrTokenController.
 * Covers QR token generation, authentication requirements, and response format.
 */
@WebMvcTest(QrTokenController.class)
@Import(SecurityConfig.class)
class QrTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QrTokenService qrService;

    // Required by SecurityConfig: prevents authenticationManager() bean creation,
    // which would otherwise need DualChainAuthenticationProvider (not in WebMvcTest slice).
    @MockBean
    private AuthenticationManager authenticationManager;

    // Required by SecurityConfig's DaoAuthenticationProvider
    @MockBean
    private CustomUserDetailsService userDetailsService;

    // ── Test 1: Happy path ────────────────────────────────────────────────────

    @Test
    @DisplayName("QR generate: authenticated user receives valid token response")
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    void generateQrToken_WithAuthenticatedUser_ReturnsTokenAndExpiry() throws Exception {
        // Arrange
        String mockQrToken = "eyJhbGciOiJIUzI1NiJ9.mock-qr-payload.signature";
        when(qrService.generateQrToken(any(UUID.class))).thenReturn(mockQrToken);

        // Act & Assert
        mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrToken").value(mockQrToken))
                .andExpect(jsonPath("$.expiresIn").value("60"));
    }

    // ── Test 2: Error path — no authentication ────────────────────────────────

    @Test
    @DisplayName("QR generate: unauthenticated request is rejected")
    void generateQrToken_WithoutAuthentication_ReturnsClientError() throws Exception {
        // Arrange — no user in context

        // Act & Assert — endpoint requires authentication
        mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(qrService);
    }

    // ── Test 3: Service is called with the correct anonymousId ────────────────

    @Test
    @DisplayName("QR generate: service receives UUID parsed from Authentication.getName()")
    @WithMockUser(username = "123e4567-e89b-12d3-a456-426614174000")
    void generateQrToken_ServiceCalledWithCorrectUuid() throws Exception {
        // Arrange
        UUID expectedId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        when(qrService.generateQrToken(expectedId)).thenReturn("token-for-user");

        // Act
        mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().isOk());

        // Assert — service was called with the UUID from the mock user's name
        verify(qrService, times(1)).generateQrToken(expectedId);
    }

    // ── Test 4: Response contains both required fields ────────────────────────

    @Test
    @DisplayName("QR generate: response always contains qrToken and expiresIn fields")
    @WithMockUser(username = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    void generateQrToken_ResponseContainsBothFields() throws Exception {
        // Arrange
        when(qrService.generateQrToken(any())).thenReturn("my-test-token");

        // Act & Assert — validate response structure
        String responseBody = mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrToken").exists())
                .andExpect(jsonPath("$.expiresIn").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody).contains("qrToken");
        assertThat(responseBody).contains("expiresIn");
    }

    // ── Test 5: expiresIn is always exactly 60 seconds ────────────────────────

    @Test
    @DisplayName("QR generate: expiresIn is fixed at 60 seconds regardless of service output")
    @WithMockUser(username = "11111111-2222-3333-4444-555555555555")
    void generateQrToken_ExpiresInIsAlways60() throws Exception {
        // Arrange
        when(qrService.generateQrToken(any())).thenReturn("any-token");

        // Act & Assert
        mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value("60"));
    }

    // ── Test 6: Edge case — service returns empty token ───────────────────────

    @Test
    @DisplayName("QR generate: empty token from service still returns 200 with empty qrToken")
    @WithMockUser(username = "ffffffff-ffff-ffff-ffff-ffffffffffff")
    void generateQrToken_EmptyTokenFromService_StillReturns200() throws Exception {
        // Arrange
        when(qrService.generateQrToken(any())).thenReturn("");

        // Act & Assert
        mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrToken").value(""));
    }
}
