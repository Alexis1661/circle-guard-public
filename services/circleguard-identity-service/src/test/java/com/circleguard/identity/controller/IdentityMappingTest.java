package com.circleguard.identity.controller;

import com.circleguard.identity.config.SecurityConfig;
import com.circleguard.identity.service.IdentityVaultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for IdentityVaultController — mapping and visitor registration.
 * Covers happy paths, edge cases, and service interaction validation.
 */
@WebMvcTest(IdentityVaultController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = {"jwt.secret=my-super-secret-test-key-must-be-at-least-32-bytes-long"})
class IdentityMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IdentityVaultService vaultService;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    // ── Test 1: Map identity — happy path ────────────────────────────────────

    @Test
    @DisplayName("Map identity: valid realIdentity returns anonymousId in response")
    void mapIdentity_WithValidRealIdentity_ReturnsAnonymousId() throws Exception {
        // Arrange
        UUID expectedAnonymousId = UUID.randomUUID();
        when(vaultService.getOrCreateAnonymousId("user@university.edu"))
                .thenReturn(expectedAnonymousId);

        // Act & Assert
        mockMvc.perform(post("/api/v1/identities/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realIdentity\": \"user@university.edu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousId").value(expectedAnonymousId.toString()));
    }

    // ── Test 2: Map identity — service is called with correct argument ─────────

    @Test
    @DisplayName("Map identity: service receives exact realIdentity string from request")
    void mapIdentity_ServiceReceivesCorrectIdentityString() throws Exception {
        // Arrange
        String realIdentity = "professor@circleguard.edu";
        UUID anonymousId = UUID.randomUUID();
        when(vaultService.getOrCreateAnonymousId(realIdentity)).thenReturn(anonymousId);

        // Act
        mockMvc.perform(post("/api/v1/identities/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realIdentity\": \"professor@circleguard.edu\"}"))
                .andExpect(status().isOk());

        // Assert — verify service was called with the exact string
        verify(vaultService, times(1)).getOrCreateAnonymousId(realIdentity);
    }

    // ── Test 3: Map identity — idempotent (same identity → same UUID) ─────────

    @Test
    @DisplayName("Map identity: calling twice with same identity returns same anonymousId")
    void mapIdentity_IdempotentForSameRealIdentity() throws Exception {
        // Arrange
        String identity = "same-user@edu.co";
        UUID fixedId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        when(vaultService.getOrCreateAnonymousId(identity)).thenReturn(fixedId);

        // Act & Assert — both calls return the same UUID
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/identities/map")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"realIdentity\": \"same-user@edu.co\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.anonymousId").value(fixedId.toString()));
        }
        verify(vaultService, times(2)).getOrCreateAnonymousId(identity);
    }

    // ── Test 4: Register visitor — happy path ─────────────────────────────────

    @Test
    @DisplayName("Visitor registration: valid request creates anonymized visitor identity")
    void registerVisitor_WithAllRequiredFields_ReturnsAnonymousId() throws Exception {
        // Arrange
        UUID visitorAnonymousId = UUID.randomUUID();
        // Service is called with "VISITOR|email|name|reason" format
        when(vaultService.getOrCreateAnonymousId(startsWith("VISITOR|")))
                .thenReturn(visitorAnonymousId);

        // Act & Assert
        mockMvc.perform(post("/api/v1/identities/visitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"name\": \"Juan Pérez\", " +
                                "\"email\": \"visitor@example.com\", " +
                                "\"reason_for_visit\": \"Medical appointment\"" +
                                "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousId").value(visitorAnonymousId.toString()));
    }

    // ── Test 5: Register visitor — identity string format validation ───────────

    @Test
    @DisplayName("Visitor registration: identity passed to service has VISITOR| prefix with email and name")
    void registerVisitor_ServiceCalledWithCorrectlyFormattedIdentityString() throws Exception {
        // Arrange
        UUID visitorId = UUID.randomUUID();
        String expectedIdentity = "VISITOR|guest@test.com|Test Guest|Lab visit";
        when(vaultService.getOrCreateAnonymousId(expectedIdentity)).thenReturn(visitorId);

        // Act
        mockMvc.perform(post("/api/v1/identities/visitor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"name\": \"Test Guest\", " +
                                "\"email\": \"guest@test.com\", " +
                                "\"reason_for_visit\": \"Lab visit\"" +
                                "}"))
                .andExpect(status().isOk());

        // Assert — verify the composite identity string format
        verify(vaultService, times(1)).getOrCreateAnonymousId(expectedIdentity);
    }

    // ── Test 6: Map identity — response contains only anonymousId field ────────

    @Test
    @DisplayName("Map identity: response body is a JSON object with anonymousId field only")
    void mapIdentity_ResponseStructure_ContainsAnonymousIdOnly() throws Exception {
        // Arrange
        UUID id = UUID.randomUUID();
        when(vaultService.getOrCreateAnonymousId(anyString())).thenReturn(id);

        // Act & Assert
        String body = mockMvc.perform(post("/api/v1/identities/map")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realIdentity\": \"check@format.com\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("anonymousId");
        assertThat(body).doesNotContain("realIdentity");
    }
}
