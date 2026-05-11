package com.circleguard.integration;

import com.circleguard.form.FormApplication;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for FORM → NOTIFICATION Kafka pipeline.
 *
 * Form service (real PostgreSQL + EmbeddedKafka) publishes events when:
 *   - A health survey is submitted              → topic: survey.submitted
 *   - A certificate is validated as APPROVED    → topic: certificate.validated
 *
 * A test @KafkaListener captures messages for assertion.
 */
@Slf4j
@DisplayName("FORM → NOTIFICATION Kafka Integration")
@SpringBootTest(classes = {FormApplication.class,
        FormNotificationKafkaTest.TestKafkaConsumerConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"survey.submitted", "certificate.validated"}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        // Flyway DISABLED: auth-service also has V1 migration in its JAR → classpath conflict.
        // Hibernate create-drop builds the schema from entities instead.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        // Exclude auto-configurations not needed by form service
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                // Spring Security from auth-service JAR — form service does NOT use it
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        // Kafka settings — form service uses JsonSerializer for values (sends Map<String,Object>)
        // Test consumer uses StringDeserializer which reads the JSON bytes as a String
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer"
})
class FormNotificationKafkaTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("circleguard_form")
            .withUsername("test")
            .withPassword("test");

    @LocalServerPort
    int port;

    @Autowired
    TestKafkaConsumerConfig kafkaConsumer;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        kafkaConsumer.surveyMessages.clear();
        kafkaConsumer.certificateMessages.clear();
    }

    // ── Test 1: Survey with symptoms → survey.submitted Kafka event ───────────

    @Test
    @Timeout(60)
    @DisplayName("POST /surveys with fever+cough publishes survey.submitted to Kafka")
    void submitSurveyWithSymptoms_publishesSurveySubmittedEvent() throws Exception {
        String anonymousId = UUID.randomUUID().toString();

        given()
                .contentType(ContentType.JSON)
                .body(surveyBody(anonymousId, true, true, "Fatigue", "2024-01-10"))
                .when()
                .post("/api/v1/surveys")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)));

        String message = kafkaConsumer.surveyMessages.poll(10, TimeUnit.SECONDS);

        assertThat(message)
                .as("survey.submitted event must be published after survey submission")
                .isNotNull()
                .contains(anonymousId);

        log.info("survey.submitted received: {}", message);
    }

    // ── Test 2: Survey without symptoms → Kafka event (hasSymptoms=false) ─────

    @Test
    @Timeout(60)
    @DisplayName("POST /surveys without symptoms publishes survey.submitted (hasSymptoms=false)")
    void submitSurveyWithoutSymptoms_publishesNegativeEvent() throws Exception {
        String anonymousId = UUID.randomUUID().toString();

        given()
                .contentType(ContentType.JSON)
                .body(surveyBody(anonymousId, false, false, null, null))
                .when()
                .post("/api/v1/surveys")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)));

        String message = kafkaConsumer.surveyMessages.poll(10, TimeUnit.SECONDS);

        assertThat(message)
                .isNotNull()
                .contains(anonymousId);

        log.info("Symptom-free survey event: {}", message);
    }

    // ── Test 3: Certificate validation → certificate.validated Kafka event ────

    @Test
    @Timeout(60)
    @DisplayName("POST /certificates/{id}/validate APPROVED publishes certificate.validated")
    void validateCertificate_publishesCertificateValidatedEvent() throws Exception {
        String anonymousId = UUID.randomUUID().toString();
        String adminId = UUID.randomUUID().toString();

        // Step 1: Create a survey with attachment path (→ becomes a certificate)
        // Note: HealthSurvey.id is UUID (String), not Integer
        String surveyId = given()
                .contentType(ContentType.JSON)
                .body(surveyBodyWithAttachment(anonymousId, true, true,
                        "Symptoms present", "2024-01-10", "/uploads/cert.pdf"))
                .when()
                .post("/api/v1/surveys")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)))
                .extract()
                .path("id");

        assertThat(surveyId).isNotNull();
        kafkaConsumer.surveyMessages.poll(5, TimeUnit.SECONDS); // drain survey event

        // Step 2: Validate the certificate
        // Controller uses @RequestParam (not @RequestBody): status=APPROVED&adminId=UUID
        given()
                .queryParam("status", "APPROVED")
                .queryParam("adminId", adminId)
                .when()
                .post("/api/v1/certificates/" + surveyId + "/validate")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(201), equalTo(204)));

        // Step 3: Verify certificate.validated published
        String certMessage = kafkaConsumer.certificateMessages.poll(10, TimeUnit.SECONDS);

        assertThat(certMessage)
                .as("certificate.validated must be published on APPROVED validation")
                .isNotNull()
                .contains(anonymousId);

        log.info("certificate.validated received: {}", certMessage);
    }

    // ── Test 4: GET /certificates/pending returns list ────────────────────────

    @Test
    @Timeout(60)
    @DisplayName("GET /certificates/pending returns a list (form service DB query works)")
    void getPendingCertificates_returnsListFromDatabase() throws Exception {
        // Create a survey with attachment first (becomes pending certificate)
        given()
                .contentType(ContentType.JSON)
                .body(surveyBodyWithAttachment(UUID.randomUUID().toString(),
                        true, false, null, null, "/uploads/evidence.jpg"))
                .post("/api/v1/surveys")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)));

        kafkaConsumer.surveyMessages.poll(5, TimeUnit.SECONDS);

        given()
                .when()
                .get("/api/v1/certificates/pending")
                .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));

        log.info("Pending certificates endpoint returned correctly from database");
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private String surveyBody(String anonId, boolean fever, boolean cough,
                               String other, String date) {
        String o = other != null ? "\"" + other + "\"" : "null";
        String d = date != null ? "\"" + date + "\"" : "null";
        return String.format(
                "{\"anonymousId\":\"%s\",\"hasFever\":%b,\"hasCough\":%b," +
                "\"otherSymptoms\":%s,\"exposureDate\":%s,\"responses\":{}}",
                anonId, fever, cough, o, d);
    }

    private String surveyBodyWithAttachment(String anonId, boolean fever, boolean cough,
                                             String other, String date, String path) {
        String o = other != null ? "\"" + other + "\"" : "null";
        String d = date != null ? "\"" + date + "\"" : "null";
        return String.format(
                "{\"anonymousId\":\"%s\",\"hasFever\":%b,\"hasCough\":%b," +
                "\"otherSymptoms\":%s,\"exposureDate\":%s," +
                "\"attachmentPath\":\"%s\",\"responses\":{}}",
                anonId, fever, cough, o, d, path);
    }

    // ── Test Kafka consumer ────────────────────────────────────────────────────

    @TestConfiguration
    static class TestKafkaConsumerConfig {

        final BlockingQueue<String> surveyMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> certificateMessages = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "survey.submitted", groupId = "test-form-survey")
        public void onSurveySubmitted(String message) {
            log.info("[TEST CONSUMER] survey.submitted: {}", message);
            surveyMessages.offer(message);
        }

        @KafkaListener(topics = "certificate.validated", groupId = "test-form-cert")
        public void onCertificateValidated(String message) {
            log.info("[TEST CONSUMER] certificate.validated: {}", message);
            certificateMessages.offer(message);
        }

        @Bean
        ConsumerFactory<String, String> testConsumerFactory(EmbeddedKafkaBroker broker) {
            return new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                    ConsumerConfig.GROUP_ID_CONFIG, "test-form-verification",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
            ));
        }
    }
}
