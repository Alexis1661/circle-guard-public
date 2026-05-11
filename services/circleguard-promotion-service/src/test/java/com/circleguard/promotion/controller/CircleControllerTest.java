package com.circleguard.promotion.controller;

import com.circleguard.promotion.model.graph.CircleNode;
import com.circleguard.promotion.security.SecurityConfig;
import com.circleguard.promotion.service.CircleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for CircleController.
 * Covers circle creation, joining, membership queries, and access control.
 */
@WebMvcTest(CircleController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = {"jwt.secret=my-super-secret-test-key-must-be-at-least-32-bytes-long"})
class CircleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CircleService circleService;

    // ── Test 1: Create circle — happy path ────────────────────────────────────

    @Test
    @DisplayName("Create circle: valid request returns created CircleNode with name and inviteCode")
    void createCircle_WithValidRequest_ReturnsCircleNode() throws Exception {
        // Arrange
        CircleNode circle = CircleNode.builder()
                .id(1L)
                .name("Lab Group A")
                .inviteCode("MESH-1234")
                .locationId("room-101")
                .isValid(true)
                .build();

        when(circleService.createCircle("Lab Group A", "room-101")).thenReturn(circle);

        // Act & Assert
        mockMvc.perform(post("/api/v1/circles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Lab Group A\", \"locationId\": \"room-101\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lab Group A"))
                .andExpect(jsonPath("$.inviteCode").value("MESH-1234"))
                .andExpect(jsonPath("$.isValid").value(true));
    }

    // ── Test 2: Join circle by invite code ────────────────────────────────────

    @Test
    @DisplayName("Join circle: valid code and anonymousId returns updated CircleNode with member")
    void joinCircle_WithValidCodeAndUser_ReturnsUpdatedCircle() throws Exception {
        // Arrange
        String inviteCode = "MESH-5678";
        String anonymousId = "anon-user-uuid";
        CircleNode updatedCircle = CircleNode.builder()
                .id(2L)
                .name("Study Circle")
                .inviteCode(inviteCode)
                .build();

        when(circleService.joinCircle(anonymousId, inviteCode)).thenReturn(updatedCircle);

        // Act & Assert
        mockMvc.perform(post("/api/v1/circles/join/{code}/user/{anonymousId}", inviteCode, anonymousId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").value(inviteCode));

        verify(circleService, times(1)).joinCircle(anonymousId, inviteCode);
    }

    // ── Test 3: Get user circles ──────────────────────────────────────────────

    @Test
    @DisplayName("Get user circles: returns list of circles the user belongs to")
    void getUserCircles_ReturnsListOfCircles() throws Exception {
        // Arrange
        String anonymousId = "my-anon-id";
        List<CircleNode> circles = List.of(
                CircleNode.builder().id(1L).name("Circle A").build(),
                CircleNode.builder().id(2L).name("Circle B").build()
        );

        when(circleService.getUserCircles(anonymousId)).thenReturn(circles);

        // Act & Assert
        mockMvc.perform(get("/api/v1/circles/user/{anonymousId}", anonymousId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Circle A"))
                .andExpect(jsonPath("$[1].name").value("Circle B"));
    }

    // ── Test 4: Toggle validity — without HEALTH_CENTER role → 403 ───────────

    @Test
    @DisplayName("Toggle validity: request without HEALTH_CENTER role is rejected with 403")
    @WithMockUser(roles = "STUDENT")
    void toggleValidity_WithoutHealthCenterRole_Returns403() throws Exception {
        // Arrange — user with STUDENT role, not HEALTH_CENTER

        // Act & Assert — method security should block this
        mockMvc.perform(patch("/api/v1/circles/{id}/validity", 1L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(circleService);
    }

    // ── Test 5: Force fence — without HEALTH_CENTER role → 403 ───────────────

    @Test
    @DisplayName("Force fence: request without HEALTH_CENTER role is rejected with 403")
    @WithMockUser(roles = "STUDENT")
    void forceFence_WithoutHealthCenterRole_Returns403() throws Exception {
        // Arrange — user with STUDENT role

        // Act & Assert
        mockMvc.perform(post("/api/v1/circles/{id}/force-fence", 1L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(circleService);
    }

    // ── Test 6: Force fence — with HEALTH_CENTER role → 200 ──────────────────

    @Test
    @DisplayName("Force fence: HEALTH_CENTER user can trigger force fence and receives 200")
    @WithMockUser(roles = "HEALTH_CENTER")
    void forceFence_WithHealthCenterRole_Returns200AndCallsService() throws Exception {
        // Arrange
        doNothing().when(circleService).forceFenceCircle(42L);

        // Act & Assert
        mockMvc.perform(post("/api/v1/circles/{id}/force-fence", 42L))
                .andExpect(status().isOk());

        verify(circleService, times(1)).forceFenceCircle(42L);
    }

    // ── Test 7: Add member to circle ─────────────────────────────────────────

    @Test
    @DisplayName("Add member: service is called with correct circleId and anonymousId")
    void addMember_ServiceCalledWithCorrectArguments() throws Exception {
        // Arrange
        Long circleId = 99L;
        String anonymousId = "new-member-uuid";
        CircleNode result = CircleNode.builder().id(circleId).name("Circle").build();

        when(circleService.addMember(circleId, anonymousId)).thenReturn(result);

        // Act
        mockMvc.perform(post("/api/v1/circles/{id}/members/{anonymousId}", circleId, anonymousId))
                .andExpect(status().isOk());

        // Assert
        verify(circleService, times(1)).addMember(circleId, anonymousId);
    }
}
