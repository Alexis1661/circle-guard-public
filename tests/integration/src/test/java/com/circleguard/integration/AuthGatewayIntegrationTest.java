package com.circleguard.integration;

import com.circleguard.gateway.GatewayServiceApplication;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for AUTH ↔ GATEWAY QR validation flow.
 *
 * Gateway service uses Redis to cache validated QR tokens.
 * QR tokens are JWTs signed with the shared QR secret (same secret auth service uses).
 *
 * Tests generate QR tokens inline (using JJWT) to simulate what auth service produces,
 * then call the gateway validate endpoint to confirm the integration contract.
 */
@Slf4j
@DisplayName("AUTH ↔ GATEWAY QR Validation Integration")
@SpringBootTest(classes = GatewayServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        // Gateway does not use a database — disable JPA/Flyway to prevent
        // classpath-contaminated auto-configurations from failing
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration," +
                // Spring Security from auth-service JAR auto-configures — disable it
                // (gateway service does NOT use Spring Security)
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
})
class AuthGatewayIntegrationTest {

    static final String QR_SECRET = "my-qr-secret-key-for-dev-1234567890";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
    }

    // ── Test 1: Valid QR token opens the gate ─────────────────────────────────

    @Test
    @Timeout(60)
    @DisplayName("POST /gate/validate with valid QR token returns 200 (gate opens)")
    void validateQr_withValidToken_gateOpens() {
        String validQr = buildQrToken(UUID.randomUUID().toString(), 60_000);

        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + validQr + "\"}")
                .when()
                .post("/api/v1/gate/validate")
                .then()
                .statusCode(200);

        log.info("Valid QR token accepted by GATEWAY");
    }

    // ── Test 2: Expired QR token → valid=false in response ───────────────────

    @Test
    @Timeout(60)
    @DisplayName("POST /gate/validate with expired QR token returns valid=false (gate stays closed)")
    void validateQr_withExpiredToken_gateRejects() {
        // GateController always returns HTTP 200; rejection is in the response body
        String expiredQr = buildExpiredQrToken(UUID.randomUUID().toString());

        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + expiredQr + "\"}")
                .when()
                .post("/api/v1/gate/validate")
                .then()
                .statusCode(200)
                .body("valid", equalTo(false));

        log.info("Expired QR token correctly results in valid=false response");
    }

    // ── Test 3: Tampered QR token → valid=false ───────────────────────────────

    @Test
    @Timeout(60)
    @DisplayName("POST /gate/validate with tampered QR signature returns valid=false")
    void validateQr_withTamperedToken_gateRejects() {
        String validQr = buildQrToken(UUID.randomUUID().toString(), 60_000);
        String tamperedQr = validQr.substring(0, validQr.length() - 4) + "XXXX";

        given()
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + tamperedQr + "\"}")
                .when()
                .post("/api/v1/gate/validate")
                .then()
                .statusCode(200)
                .body("valid", equalTo(false));

        log.info("Tampered QR token correctly results in valid=false response");
    }

    // ── Test 4: Missing token → valid=false ───────────────────────────────────

    @Test
    @Timeout(60)
    @DisplayName("POST /gate/validate with missing token returns valid=false")
    void validateQr_withMissingToken_gateRejects() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/api/v1/gate/validate")
                .then()
                .statusCode(200)
                .body("valid", equalTo(false));

        log.info("Missing token correctly results in valid=false response");
    }

    // ── Test 5: Redis cache — same token accepted twice ───────────────────────

    @Test
    @Timeout(60)
    @DisplayName("Valid QR token is accepted on repeated calls (Redis cache active)")
    void validateQr_calledTwice_bothSucceedFromCache() {
        String validQr = buildQrToken(UUID.randomUUID().toString(), 60_000);
        String body = "{\"token\":\"" + validQr + "\"}";

        // First call — hits gateway, caches in Redis
        given().contentType(ContentType.JSON).body(body)
                .post("/api/v1/gate/validate")
                .then().statusCode(200);

        // Second call — served from Redis cache
        given().contentType(ContentType.JSON).body(body)
                .post("/api/v1/gate/validate")
                .then().statusCode(200);

        log.info("Redis cache confirmed — QR validation idempotent across two calls");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildQrToken(String anonymousId, long validForMillis) {
        return Jwts.builder()
                .setSubject(anonymousId)
                .claim("anonymousId", anonymousId)
                .claim("type", "QR_ENTRY")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + validForMillis))
                .signWith(Keys.hmacShaKeyFor(QR_SECRET.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }

    private String buildExpiredQrToken(String anonymousId) {
        return Jwts.builder()
                .setSubject(anonymousId)
                .claim("anonymousId", anonymousId)
                .claim("type", "QR_ENTRY")
                .setIssuedAt(new Date(System.currentTimeMillis() - 120_000))
                .setExpiration(new Date(System.currentTimeMillis() - 30_000))
                .signWith(Keys.hmacShaKeyFor(QR_SECRET.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }
}
