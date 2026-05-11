package com.circleguard.e2e.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.experimental.UtilityClass;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Static factory for test data used across E2E flows.
 *
 * Provides realistic, coherent data that represents real campus users, buildings,
 * and health scenarios. All tokens are signed with the shared JWT secret.
 */
@UtilityClass
public class TestDataBuilder {

    // ── Shared secrets (must match application.yml) ───────────────────────
    public static final String JWT_SECRET    = "my-super-secret-dev-key-32-chars-long-12345678";
    public static final String QR_SECRET     = "my-qr-secret-key-for-dev-1234567890";
    public static final long   JWT_TTL_MS    = 3_600_000L;

    // ── Test user IDs ─────────────────────────────────────────────────────
    public static final String ADMIN_USER        = "admin";
    public static final String STUDENT_USER      = "student01";
    public static final String HEALTH_ADMIN_USER = "health_admin";

    // ── Sample campus locations ───────────────────────────────────────────
    public static final String BUILDING_CODE_A   = "BLDG-A";
    public static final String BUILDING_CODE_B   = "BLDG-B";
    public static final String MAC_WIFI_AP1      = "AA:BB:CC:DD:EE:FF";
    public static final String MAC_WIFI_AP2      = "11:22:33:44:55:66";

    // ── JWT builders ──────────────────────────────────────────────────────

    /** Builds a JWT with the given username and list of authorities (ADMIN, HEALTH_CENTER, etc.). */
    public static String buildJwt(String username, List<String> authorities) {
        return Jwts.builder()
                .setSubject(username)
                .claim("roles", authorities)
                .claim("anonymousId", UUID.randomUUID().toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + JWT_TTL_MS))
                .signWith(
                        Keys.hmacShaKeyFor(JWT_SECRET.getBytes()),
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    /** Admin JWT — grants ADMIN authority (required for building/floor/AP creation). */
    public static String adminJwt() {
        return buildJwt(ADMIN_USER, List.of("ADMIN"));
    }

    /**
     * Promotion service admin JWT.
     * The promotion service's JwtAuthenticationFilter reads "permissions" claim (not "roles").
     * This JWT includes both "roles" (for other services) and "permissions" (for promotion service).
     */
    public static String promotionAdminJwt() {
        return Jwts.builder()
                .setSubject(ADMIN_USER)
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("ADMIN"))
                .claim("anonymousId", UUID.randomUUID().toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + JWT_TTL_MS))
                .signWith(
                        Keys.hmacShaKeyFor(JWT_SECRET.getBytes()),
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    /** Student JWT — standard user with no special authorities. */
    public static String studentJwt() {
        return buildJwt(STUDENT_USER, List.of("STUDENT"));
    }

    /** Health center JWT — grants HEALTH_CENTER role for medical operations. */
    public static String healthCenterJwt() {
        return buildJwt(HEALTH_ADMIN_USER, List.of("HEALTH_CENTER"));
    }

    /** Builds a valid QR token (JWT signed with QR secret, valid for the given duration). */
    public static String buildQrToken(String anonymousId, long validForMillis) {
        return Jwts.builder()
                .setSubject(anonymousId)
                .claim("anonymousId", anonymousId)
                .claim("type", "QR_ENTRY")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + validForMillis))
                .signWith(
                        Keys.hmacShaKeyFor(QR_SECRET.getBytes()),
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    /** Builds an already-expired QR token. */
    public static String buildExpiredQrToken(String anonymousId) {
        return Jwts.builder()
                .setSubject(anonymousId)
                .claim("anonymousId", anonymousId)
                .claim("type", "QR_ENTRY")
                .setIssuedAt(new Date(System.currentTimeMillis() - 120_000))
                .setExpiration(new Date(System.currentTimeMillis() - 30_000))
                .signWith(
                        Keys.hmacShaKeyFor(QR_SECRET.getBytes()),
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    // ── JSON request bodies ───────────────────────────────────────────────

    /** Campus building JSON body. */
    public static String buildingJson(String name, String code) {
        return String.format(
                "{\"name\":\"%s\",\"code\":\"%s\",\"description\":\"E2E test building\"," +
                "\"latitude\":4.6297,\"longitude\":-74.0817,\"address\":\"Campus Norte\"}",
                name, code);
    }

    /** Campus floor JSON body. */
    public static String floorJson(int floorNumber, String name) {
        return String.format(
                "{\"floorNumber\":%d,\"name\":\"%s\"}",
                floorNumber, name);
    }

    /** Generates a unique MAC address to avoid DB unique constraint violations across tests. */
    public static String uniqueMac() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return hex.substring(0,2) + ":" + hex.substring(2,4) + ":" + hex.substring(4,6) + ":" +
               hex.substring(6,8) + ":" + hex.substring(8,10) + ":" + hex.substring(10,12);
    }

    /** WiFi access point JSON body. */
    public static String accessPointJson(String macAddress, String name, double x, double y) {
        return String.format(Locale.US,
                "{\"macAddress\":\"%s\",\"name\":\"%s\",\"coordinateX\":%.2f,\"coordinateY\":%.2f}",
                macAddress, name, x, y);
    }

    /** Health survey JSON body. */
    public static String surveyJson(String anonymousId, boolean fever, boolean cough,
                                    String otherSymptoms, String exposureDate) {
        String symptoms = otherSymptoms != null ? "\"" + otherSymptoms + "\"" : "null";
        String date     = exposureDate != null ? "\"" + exposureDate + "\"" : "null";
        return String.format(
                "{\"anonymousId\":\"%s\",\"hasFever\":%b,\"hasCough\":%b," +
                "\"otherSymptoms\":%s,\"exposureDate\":%s,\"responses\":{}}",
                anonymousId, fever, cough, symptoms, date);
    }

    /** Health survey with attachment path (becomes a certificate). */
    public static String surveyWithAttachmentJson(String anonymousId, boolean fever, boolean cough,
                                                   String otherSymptoms, String exposureDate,
                                                   String attachmentPath) {
        String symptoms = otherSymptoms != null ? "\"" + otherSymptoms + "\"" : "null";
        String date     = exposureDate != null ? "\"" + exposureDate + "\"" : "null";
        return String.format(
                "{\"anonymousId\":\"%s\",\"hasFever\":%b,\"hasCough\":%b," +
                "\"otherSymptoms\":%s,\"exposureDate\":%s," +
                "\"attachmentPath\":\"%s\",\"responses\":{}}",
                anonymousId, fever, cough, symptoms, date, attachmentPath);
    }

    /** Questionnaire JSON body. */
    public static String questionnaireJson(String title, String description) {
        return String.format(
                "{\"title\":\"%s\",\"description\":\"%s\",\"version\":1,\"isActive\":false}",
                title, description);
    }

    /** Kafka status change event JSON. */
    public static String statusChangedEvent(String anonymousId, String status) {
        return String.format(
                "{\"anonymousId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                anonymousId, status, java.time.Instant.now());
    }

    /** Kafka priority alert event JSON. */
    public static String priorityAlertEvent(String anonymousId, String status,
                                             String eventType, int affectedCount) {
        return String.format(
                "{\"anonymousId\":\"%s\",\"status\":\"%s\",\"affectedCount\":%d," +
                "\"eventType\":\"%s\",\"timestamp\":\"%s\"}",
                anonymousId, status, affectedCount, eventType, java.time.Instant.now());
    }

    /** Kafka circle fenced event JSON. */
    public static String circleFencedEvent(String circleId, String locationId, String name) {
        return String.format(
                "{\"circleId\":\"%s\",\"locationId\":\"%s\",\"name\":\"%s\",\"timestamp\":\"%s\"}",
                circleId, locationId, name, java.time.Instant.now());
    }

    /** Login request JSON. */
    public static String loginJson(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }
}
