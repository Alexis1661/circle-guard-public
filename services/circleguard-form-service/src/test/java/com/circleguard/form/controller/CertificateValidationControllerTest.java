package com.circleguard.form.controller;

import com.circleguard.form.model.HealthSurvey;
import com.circleguard.form.model.ValidationStatus;
import com.circleguard.form.service.HealthSurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for CertificateValidationController.
 * Covers: get pending surveys, validate with APPROVED/REJECTED, edge cases.
 * Form service has no Spring Security, so no auth mocking needed.
 */
@WebMvcTest(CertificateValidationController.class)
class CertificateValidationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthSurveyService surveyService;

    // ── Test 1: Get pending surveys — happy path ──────────────────────────────

    @Test
    @DisplayName("Pending surveys: returns list of surveys awaiting validation")
    void getPendingSurveys_ReturnsPendingList() throws Exception {
        // Arrange
        HealthSurvey s1 = HealthSurvey.builder()
                .id(UUID.randomUUID())
                .anonymousId(UUID.randomUUID())
                .validationStatus(ValidationStatus.PENDING)
                .build();
        HealthSurvey s2 = HealthSurvey.builder()
                .id(UUID.randomUUID())
                .anonymousId(UUID.randomUUID())
                .validationStatus(ValidationStatus.PENDING)
                .build();

        when(surveyService.getPendingSurveys()).thenReturn(List.of(s1, s2));

        // Act & Assert
        mockMvc.perform(get("/api/v1/certificates/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].validationStatus").value("PENDING"));
    }

    // ── Test 2: Validate with APPROVED status ─────────────────────────────────

    @Test
    @DisplayName("Validate certificate: APPROVED status invokes service with correct params")
    void validateCertificate_WithApprovedStatus_CallsServiceCorrectly() throws Exception {
        // Arrange
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        doNothing().when(surveyService).validateSurvey(any(), any(), any());

        // Act & Assert
        mockMvc.perform(post("/api/v1/certificates/{id}/validate", surveyId)
                        .param("status", "APPROVED")
                        .param("adminId", adminId.toString()))
                .andExpect(status().isOk());

        verify(surveyService, times(1))
                .validateSurvey(eq(surveyId), eq(ValidationStatus.APPROVED), eq(adminId));
    }

    // ── Test 3: Validate with REJECTED status ─────────────────────────────────

    @Test
    @DisplayName("Validate certificate: REJECTED status also invokes service successfully")
    void validateCertificate_WithRejectedStatus_CallsServiceWithRejected() throws Exception {
        // Arrange
        UUID surveyId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        doNothing().when(surveyService).validateSurvey(any(), any(), any());

        // Act & Assert
        mockMvc.perform(post("/api/v1/certificates/{id}/validate", surveyId)
                        .param("status", "REJECTED")
                        .param("adminId", adminId.toString()))
                .andExpect(status().isOk());

        verify(surveyService).validateSurvey(eq(surveyId), eq(ValidationStatus.REJECTED), eq(adminId));
    }

    // ── Test 4: Missing adminId param → 400 ──────────────────────────────────

    @Test
    @DisplayName("Validate certificate: missing adminId parameter returns 400 Bad Request")
    void validateCertificate_MissingAdminId_Returns400() throws Exception {
        // Arrange
        UUID surveyId = UUID.randomUUID();

        // Act & Assert — adminId is required, missing it should cause binding failure
        mockMvc.perform(post("/api/v1/certificates/{id}/validate", surveyId)
                        .param("status", "APPROVED"))
                // Missing adminId causes Spring MVC to return 400
                .andExpect(status().isBadRequest());

        verifyNoInteractions(surveyService);
    }

    // ── Test 5: Pending surveys — empty list when no pending surveys ──────────

    @Test
    @DisplayName("Pending surveys: returns empty array when no pending surveys exist")
    void getPendingSurveys_WhenNoPending_ReturnsEmptyList() throws Exception {
        // Arrange
        when(surveyService.getPendingSurveys()).thenReturn(List.of());

        // Act & Assert
        String body = mockMvc.perform(get("/api/v1/certificates/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).isEqualTo("[]");
    }
}
