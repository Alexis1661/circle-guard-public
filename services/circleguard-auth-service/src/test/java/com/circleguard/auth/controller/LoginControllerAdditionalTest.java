package com.circleguard.auth.controller;

import com.circleguard.auth.client.IdentityClient;
import com.circleguard.auth.security.SecurityConfig;
import com.circleguard.auth.service.CustomUserDetailsService;
import com.circleguard.auth.service.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Additional unit tests for LoginController — covers failure paths,
 * visitor handoff, and edge cases not covered by LoginControllerTest.
 */
@WebMvcTest(LoginController.class)
@Import(SecurityConfig.class)
class LoginControllerAdditionalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthenticationManager authManager;

    @MockBean
    private JwtTokenService jwtService;

    @MockBean
    private IdentityClient identityClient;

    @MockBean
    private CustomUserDetailsService userDetailsService;

    // ── Test 1: Error path — invalid credentials ──────────────────────────────

    @Test
    @DisplayName("Login: wrong credentials returns 401 with error message")
    void login_WithInvalidCredentials_Returns401WithMessage() throws Exception {
        // Arrange — authManager throws BadCredentialsException
        when(authManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        // Act & Assert
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"unknown\", \"password\": \"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Verify identity service was never called on auth failure
        verifyNoInteractions(identityClient);
        assertThat(response).doesNotContain("token");
    }

    // ── Test 2: Edge case — null password ────────────────────────────────────

    @Test
    @DisplayName("Login: null password triggers auth failure and returns 401")
    void login_WithNullPassword_Returns401() throws Exception {
        // Arrange
        when(authManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("null password"));

        // Act & Assert
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"testuser\", \"password\": null}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Test 3: Visitor handoff — happy path ─────────────────────────────────

    @Test
    @DisplayName("Visitor handoff: valid anonymousId returns token and handoffPayload")
    void generateVisitorHandoff_WithValidAnonymousId_ReturnsTokenAndPayload() throws Exception {
        // Arrange
        UUID anonymousId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String visitorToken = "visitor-jwt-token";

        when(jwtService.generateToken(eq(anonymousId), any()))
                .thenReturn(visitorToken);

        // Act & Assert
        mockMvc.perform(post("/api/v1/auth/visitor/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anonymousId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(visitorToken))
                .andExpect(jsonPath("$.handoffPayload").exists());
    }

    // ── Test 4: Visitor handoff — missing anonymousId → 400 ──────────────────

    @Test
    @DisplayName("Visitor handoff: missing anonymousId field returns 400 Bad Request")
    void generateVisitorHandoff_WithoutAnonymousId_Returns400() throws Exception {
        // Arrange — body does not contain anonymousId

        // Act & Assert
        mockMvc.perform(post("/api/v1/auth/visitor/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jwtService);
    }

    // ── Test 5: Login response format validation ───────────────────────────────

    @Test
    @DisplayName("Login: successful response contains token, anonymousId, and type=Bearer")
    void login_Success_ResponseContainsAllRequiredFields() throws Exception {
        // Arrange
        UUID anonymousId = UUID.randomUUID();
        String token = "valid-jwt-token";

        var auth = Mockito.mock(org.springframework.security.core.Authentication.class);
        when(authManager.authenticate(any())).thenReturn(auth);
        when(identityClient.getAnonymousId(any())).thenReturn(anonymousId);
        when(jwtService.generateToken(eq(anonymousId), eq(auth))).thenReturn(token);

        // Act & Assert — validate full response structure
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"admin\", \"password\": \"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.anonymousId").value(anonymousId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("Bearer");
        assertThat(body).contains(anonymousId.toString());
    }
}
