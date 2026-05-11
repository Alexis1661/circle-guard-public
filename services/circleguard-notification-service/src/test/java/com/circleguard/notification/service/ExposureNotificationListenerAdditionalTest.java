package com.circleguard.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

/**
 * Additional unit tests for ExposureNotificationListener.
 * Covers: status change event parsing, LMS sync, ACTIVE status skipped.
 */
@ExtendWith(MockitoExtension.class)
class ExposureNotificationListenerAdditionalTest {

    @Mock
    private NotificationDispatcher dispatcher;

    @Mock
    private LmsService lmsService;

    @InjectMocks
    private ExposureNotificationListener listener;

    // ObjectMapper is used internally — inject a real one via constructor
    private ExposureNotificationListener listenerWithRealMapper;

    @BeforeEach
    void setUp() {
        listenerWithRealMapper = new ExposureNotificationListener(dispatcher, new ObjectMapper(), lmsService);
    }

    // ── Test 1: SUSPECT status triggers dispatcher and LMS sync ──────────────

    @Test
    @DisplayName("Status change: SUSPECT status dispatches notification and syncs LMS")
    void handleStatusChange_SuspectStatus_DispatchesAndSyncsLms() {
        // Arrange
        String event = "{\"anonymousId\": \"user-001\", \"status\": \"SUSPECT\"}";
        doNothing().when(dispatcher).dispatch(anyString(), anyString());
        when(lmsService.syncRemoteAttendance(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        listenerWithRealMapper.handleStatusChange(event);

        // Assert
        verify(dispatcher, times(1)).dispatch("user-001", "SUSPECT");
        verify(lmsService, times(1)).syncRemoteAttendance("user-001", "SUSPECT");
    }

    // ── Test 2: ACTIVE status is skipped — no notification dispatched ─────────

    @Test
    @DisplayName("Status change: ACTIVE status is skipped — no notification or LMS sync")
    void handleStatusChange_ActiveStatus_DoesNotDispatch() {
        // Arrange — ACTIVE is the normal status, no action needed
        String event = "{\"anonymousId\": \"user-002\", \"status\": \"ACTIVE\"}";

        // Act
        listenerWithRealMapper.handleStatusChange(event);

        // Assert — neither dispatcher nor LMS should be called for ACTIVE
        verifyNoInteractions(dispatcher);
        verifyNoInteractions(lmsService);
    }

    // ── Test 3: CONFIRMED status triggers full notification pipeline ──────────

    @Test
    @DisplayName("Status change: CONFIRMED status dispatches and syncs LMS")
    void handleStatusChange_ConfirmedStatus_DispatchesAndSyncsLms() {
        // Arrange
        String event = "{\"anonymousId\": \"user-003\", \"status\": \"CONFIRMED\"}";
        doNothing().when(dispatcher).dispatch(anyString(), anyString());
        when(lmsService.syncRemoteAttendance(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        listenerWithRealMapper.handleStatusChange(event);

        // Assert
        verify(dispatcher).dispatch("user-003", "CONFIRMED");
        verify(lmsService).syncRemoteAttendance("user-003", "CONFIRMED");
    }

    // ── Test 4: Malformed JSON is handled without throwing ────────────────────

    @Test
    @DisplayName("Status change: malformed JSON is logged and does not propagate exception")
    void handleStatusChange_MalformedJson_DoesNotThrow() {
        // Arrange — completely invalid JSON
        String malformedEvent = "{ this is not json }}}";

        // Act — should not throw
        listenerWithRealMapper.handleStatusChange(malformedEvent);

        // Assert — no dispatch on parse failure
        verifyNoInteractions(dispatcher);
        verifyNoInteractions(lmsService);
    }

    // ── Test 5: Missing status field defaults to UNKNOWN — no notification ────

    @Test
    @DisplayName("Status change: event without status field uses UNKNOWN default — skipped")
    void handleStatusChange_MissingStatusField_TreatedAsUnknownAndSkipped() {
        // Arrange — only userId, no status
        String event = "{\"anonymousId\": \"user-004\"}";

        // Act
        listenerWithRealMapper.handleStatusChange(event);

        // Assert — UNKNOWN is excluded from dispatch (per listener logic)
        verifyNoInteractions(dispatcher);
        verifyNoInteractions(lmsService);
    }
}
