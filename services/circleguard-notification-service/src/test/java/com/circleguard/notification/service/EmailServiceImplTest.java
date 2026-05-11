package com.circleguard.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailServiceImpl.
 * Covers successful delivery, SMTP failure, retry logging, and recovery.
 *
 * Note: @Async is bypassed in unit tests (no Spring context).
 * The CompletableFuture is resolved synchronously here.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private EmailServiceImpl emailService;

    // ── Test 1: Happy path — email sent successfully ──────────────────────────

    @Test
    @DisplayName("Send email: successful delivery calls mailSender.send() and logs SUCCESS")
    void sendAsync_Success_CallsMailSenderAndLogsSuccess() throws Exception {
        // Arrange
        String userId = "user-42";
        String message = "You have been exposed to COVID-19.";
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // Act
        CompletableFuture<Void> result = emailService.sendAsync(userId, message);
        result.get(); // wait for completion (bypass @Async in unit test)

        // Assert
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        verify(auditLogService, times(1))
                .logDelivery(eq(userId), eq("EMAIL"), eq("SUCCESS"), anyString());
    }

    // ── Test 2: Error path — SMTP failure logs RETRY and rethrows ────────────

    @Test
    @DisplayName("Send email: SMTP failure logs RETRY status and rethrows for retry mechanism")
    void sendAsync_SmtpFailure_LogsRetryAndRethrows() {
        // Arrange
        String userId = "user-smtp-fail";
        String message = "Health alert.";
        doThrow(new MailSendException("SMTP connection refused"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        // Act & Assert — exception is rethrown to trigger Spring Retry
        assertThatThrownBy(() -> emailService.sendAsync(userId, message).get())
                .isInstanceOf(Exception.class);

        // Verify RETRY was logged
        verify(auditLogService, times(1))
                .logDelivery(eq(userId), eq("EMAIL"), eq("RETRY"), anyString());
        verify(auditLogService, never())
                .logDelivery(eq(userId), eq("EMAIL"), eq("SUCCESS"), any());
    }

    // ── Test 3: Edge case — email recipient is userId@example.com ────────────

    @Test
    @DisplayName("Send email: recipient address is derived from userId + @example.com")
    void sendAsync_RecipientIsUserIdAtExampleCom() throws Exception {
        // Arrange
        String userId = "testuser";
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // Act
        emailService.sendAsync(userId, "Test message").get();

        // Assert — capture the SimpleMailMessage sent
        verify(mailSender).send(argThat((SimpleMailMessage msg) ->
                msg.getTo() != null &&
                msg.getTo().length == 1 &&
                msg.getTo()[0].equals(userId + "@example.com")
        ));
    }

    // ── Test 4: Edge case — email subject is always the standard alert subject ─

    @Test
    @DisplayName("Send email: subject is always 'CircleGuard Health Alert'")
    void sendAsync_SubjectIsAlwaysHealthAlert() throws Exception {
        // Arrange
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // Act
        emailService.sendAsync("any-user", "any message").get();

        // Assert
        verify(mailSender).send(argThat((SimpleMailMessage msg) ->
                "CircleGuard Health Alert".equals(msg.getSubject())
        ));
    }

    // ── Test 5: Recovery — after max retries, FAILED is logged ───────────────

    @Test
    @DisplayName("Recover: after max retries exhausted, FAILED is logged and failed future returned")
    void recover_AfterMaxRetries_LogsFailedAndReturnsFailed() {
        // Arrange
        String userId = "user-recover";
        Exception cause = new MailSendException("permanent SMTP failure");

        // Act — call recover() directly (simulates Spring Retry exhaustion)
        CompletableFuture<Void> future = emailService.recover(cause, userId, "message");

        // Assert
        verify(auditLogService, times(1))
                .logDelivery(eq(userId), eq("EMAIL"), eq("FAILED"), isNull());
        assertThat(future.isCompletedExceptionally()).isTrue();
    }
}
