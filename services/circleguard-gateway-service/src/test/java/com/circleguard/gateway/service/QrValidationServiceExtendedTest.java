package com.circleguard.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extended unit tests for QrValidationService.
 * Covers: POTENTIAL denial, expired token, null status (GREEN), null token.
 */
class QrValidationServiceExtendedTest {

    private QrValidationService service;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private final String secret = "my-super-secret-test-key-32-chars-long";

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new QrValidationService(redisTemplate);
        ReflectionTestUtils.setField(service, "qrSecret", secret);
    }

    // ── Test 1: POTENTIAL status → access denied ──────────────────────────────

    @Test
    @DisplayName("Validate: user with POTENTIAL Redis status receives RED denial")
    void validateToken_UserWithPotentialStatus_DeniesAccess() {
        // Arrange
        String anonymousId = UUID.randomUUID().toString();
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn("POTENTIAL");

        // Act
        QrValidationService.ValidationResult result = service.validateToken(token);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    // ── Test 2: Expired token → invalid result ────────────────────────────────

    @Test
    @DisplayName("Validate: expired JWT returns invalid result with RED status")
    void validateToken_ExpiredToken_ReturnsInvalidResult() {
        // Arrange — token expired 1 second ago
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        String expiredToken = Jwts.builder()
                .setSubject(UUID.randomUUID().toString())
                .setExpiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        // Act
        QrValidationService.ValidationResult result = service.validateToken(expiredToken);

        // Assert — exception caught, returns invalid
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
        assertThat(result.message()).isEqualTo("Invalid or Expired Token");
    }

    // ── Test 3: Null Redis status → GREEN (no health risk flagged) ────────────

    @Test
    @DisplayName("Validate: user with no Redis status entry (null) receives GREEN access")
    void validateToken_NullRedisStatus_AllowsAccess() {
        // Arrange
        String anonymousId = UUID.randomUUID().toString();
        Key key = Keys.hmacShaKeyFor(secret.getBytes());
        String token = Jwts.builder()
                .setSubject(anonymousId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        Mockito.when(valueOps.get("user:status:" + anonymousId)).thenReturn(null);

        // Act
        QrValidationService.ValidationResult result = service.validateToken(token);

        // Assert — null means no flag → GREEN
        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo("GREEN");
        assertThat(result.message()).isEqualTo("Welcome to Campus");
    }

    // ── Test 4: Completely invalid token string ────────────────────────────────

    @Test
    @DisplayName("Validate: completely malformed token string returns invalid result")
    void validateToken_MalformedToken_ReturnsInvalidResult() {
        // Arrange
        String malformedToken = "not.a.valid.jwt.token.at.all";

        // Act
        QrValidationService.ValidationResult result = service.validateToken(malformedToken);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }

    // ── Test 5: Null token ────────────────────────────────────────────────────

    @Test
    @DisplayName("Validate: null token is handled gracefully and returns invalid result")
    void validateToken_NullToken_ReturnsInvalidResult() {
        // Act — service must handle null without throwing NullPointerException
        QrValidationService.ValidationResult result = service.validateToken(null);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }
}
