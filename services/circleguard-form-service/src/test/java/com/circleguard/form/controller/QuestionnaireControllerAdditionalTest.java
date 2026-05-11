package com.circleguard.form.controller;

import com.circleguard.form.model.Questionnaire;
import com.circleguard.form.service.QuestionnaireService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Additional unit tests for QuestionnaireController.
 * Covers: no active questionnaire (404), activate, list all, create validation.
 * Form service has no security configuration.
 */
@WebMvcTest(QuestionnaireController.class)
class QuestionnaireControllerAdditionalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuestionnaireService service;

    // ── Test 1: Get active — none exists returns 404 ──────────────────────────

    @Test
    @DisplayName("Get active questionnaire: when no active exists, returns 404 Not Found")
    void getActive_WhenNoneExists_Returns404() throws Exception {
        // Arrange
        when(service.getActiveQuestionnaire()).thenReturn(Optional.empty());

        // Act & Assert
        mockMvc.perform(get("/api/v1/questionnaires/active"))
                .andExpect(status().isNotFound());
    }

    // ── Test 2: Get all questionnaires ────────────────────────────────────────

    @Test
    @DisplayName("Get all questionnaires: returns list with all questionnaires including inactive")
    void getAllQuestionnaires_ReturnsFullList() throws Exception {
        // Arrange
        List<Questionnaire> all = List.of(
                Questionnaire.builder().id(UUID.randomUUID()).title("Survey v1").isActive(false).version(1).build(),
                Questionnaire.builder().id(UUID.randomUUID()).title("Survey v2").isActive(true).version(2).build()
        );
        when(service.getAllQuestionnaires()).thenReturn(all);

        // Act & Assert
        mockMvc.perform(get("/api/v1/questionnaires"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Survey v1"))
                .andExpect(jsonPath("$[1].title").value("Survey v2"));
    }

    // ── Test 3: Activate questionnaire — service is called ────────────────────

    @Test
    @DisplayName("Activate questionnaire: service.activateQuestionnaire called with correct UUID")
    void activateQuestionnaire_CallsServiceWithCorrectId() throws Exception {
        // Arrange
        UUID id = UUID.randomUUID();
        doNothing().when(service).activateQuestionnaire(id);

        // Act & Assert
        mockMvc.perform(post("/api/v1/questionnaires/{id}/activate", id))
                .andExpect(status().isOk());

        verify(service, times(1)).activateQuestionnaire(id);
    }

    // ── Test 4: Create questionnaire with version field ───────────────────────

    @Test
    @DisplayName("Create questionnaire: version and title are persisted via service.saveQuestionnaire")
    void createQuestionnaire_VersionAndTitlePersisted() throws Exception {
        // Arrange
        UUID newId = UUID.randomUUID();
        Questionnaire saved = Questionnaire.builder()
                .id(newId)
                .title("Health Check v3")
                .version(3)
                .isActive(false)
                .build();

        when(service.saveQuestionnaire(any(Questionnaire.class))).thenReturn(saved);

        // Act & Assert
        mockMvc.perform(post("/api/v1/questionnaires")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Health Check v3\", \"version\": 3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Health Check v3"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.id").value(newId.toString()));

        verify(service, times(1)).saveQuestionnaire(any(Questionnaire.class));
    }

    // ── Test 5: Create questionnaire — response contains id field ─────────────

    @Test
    @DisplayName("Create questionnaire: response always includes a generated UUID id")
    void createQuestionnaire_ResponseContainsGeneratedId() throws Exception {
        // Arrange
        UUID generatedId = UUID.randomUUID();
        Questionnaire result = Questionnaire.builder()
                .id(generatedId)
                .title("Any")
                .version(1)
                .build();

        when(service.saveQuestionnaire(any())).thenReturn(result);

        // Act & Assert
        String body = mockMvc.perform(post("/api/v1/questionnaires")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Any\", \"version\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(generatedId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains(generatedId.toString());
    }
}
