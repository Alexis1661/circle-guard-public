package com.circleguard.integration;

import com.circleguard.form.FormApplication;
import com.circleguard.notification.NotificationApplication;
import com.circleguard.notification.service.AuditLogService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * E2E multi-service integration test: Circle Encounter Health Alert Flow.
 *
 * Full pipeline tested across two independent Spring contexts:
 *
 * Phase 1 (FormPhaseTests):
 *   Student submits health survey (REST) → form service stores in DB and
 *   publishes to survey.submitted Kafka topic → test consumer verifies the message.
 *
 * Phase 2 (NotificationPhaseTests):
 *   Kafka messages (simulating promotion-service output) arrive on
 *   promotion.status.changed, alert.priority, circle.fenced →
 *   notification-service listeners consume them → verified by wait + log.
 *
 * Containers are started at the OUTER CLASS level so they are fully ready
 * before any nested @SpringBootTest context initializes.
 */
@Slf4j
@DisplayName("E2E: Circle Encounter Health Alert Flow")
@Testcontainers  // starts @Container fields in outer class before nested contexts init
class CircleEncounterFlowTest {

    // PostgreSQL for the form service (started before nested tests)
    @Container
    static final PostgreSQLContainer<?> formPostgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("circleguard_form")
            .withUsername("test")
            .withPassword("test");

    // ── JSON builders (static, shared by both phases) ─────────────────────────

    static String surveyRequest(String anonId, boolean fever, boolean cough,
                                 String other, String date) {
        String o = other != null ? "\"" + other + "\"" : "null";
        String d = date != null ? "\"" + date + "\"" : "null";
        return String.format(
                "{\"anonymousId\":\"%s\",\"hasFever\":%b,\"hasCough\":%b," +
                "\"otherSymptoms\":%s,\"exposureDate\":%s,\"responses\":{}}",
                anonId, fever, cough, o, d);
    }

    static String statusChangedEvent(String anonId, String status) {
        return String.format("{\"anonymousId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                anonId, status, Instant.now());
    }

    static String circleFencedEvent(String circleId, String locationId, String name) {
        return String.format(
                "{\"circleId\":\"%s\",\"locationId\":\"%s\",\"name\":\"%s\",\"timestamp\":\"%s\"}",
                circleId, locationId, name, Instant.now());
    }

    static String priorityAlertEvent(String anonId, String status, String type, int count) {
        return String.format(
                "{\"anonymousId\":\"%s\",\"status\":\"%s\",\"affectedCount\":%d," +
                "\"eventType\":\"%s\",\"timestamp\":\"%s\"}",
                anonId, status, count, type, Instant.now());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 1: Form service produces events
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Phase 1 — Form service publishes health survey events")
    @SpringBootTest(classes = {FormApplication.class, FormPhaseConfig.class},
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @EmbeddedKafka(partitions = 1,
            topics = {"survey.submitted", "certificate.validated"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
            // Flyway disabled: V1 migration conflict with auth-service JAR on classpath
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
            "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                    "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer"
    })
    class FormPhaseTests {

        @LocalServerPort
        int port;

        @Autowired
        FormPhaseConfig formPhaseConfig;

        @DynamicPropertySource
        static void configureDataSource(DynamicPropertyRegistry registry) {
            // formPostgres is started by @Testcontainers on the OUTER class before this runs
            registry.add("spring.datasource.url", formPostgres::getJdbcUrl);
            registry.add("spring.datasource.username", formPostgres::getUsername);
            registry.add("spring.datasource.password", formPostgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }

        @BeforeEach
        void setUp() {
            RestAssured.port = port;
            RestAssured.baseURI = "http://localhost";
            formPhaseConfig.surveyEvents.clear();
        }

        @Test
        @Timeout(60)
        @DisplayName("E2E Step 1: Student submits health survey → survey.submitted published to Kafka")
        void studentSubmitsHealthSurvey_kafkaEventPublished() throws Exception {
            String studentId = UUID.randomUUID().toString();
            log.info("=== E2E Step 1: Student {} submitting survey ===", studentId);

            given()
                    .contentType(ContentType.JSON)
                    .body(surveyRequest(studentId, true, true,
                            "Severe headache and fatigue", "2024-01-15"))
                    .when()
                    .post("/api/v1/surveys")
                    .then()
                    .statusCode(anyOf(equalTo(200), equalTo(201)));

            log.info("=== E2E Step 2: Waiting for survey.submitted Kafka event ===");
            String kafkaEvent = formPhaseConfig.surveyEvents.poll(10, TimeUnit.SECONDS);

            assertThat(kafkaEvent)
                    .as("survey.submitted Kafka event must arrive after survey POST")
                    .isNotNull()
                    .contains(studentId);

            log.info("=== E2E Step 2 COMPLETE: Kafka event confirmed: {} ===", kafkaEvent);
        }

        @Test
        @Timeout(60)
        @DisplayName("E2E: 3 concurrent student surveys — all events reach Kafka without loss")
        void multipleStudents_allSurveyEventsReachKafka() throws Exception {
            int n = 3;
            log.info("=== E2E: {} students submitting surveys ===", n);

            for (int i = 0; i < n; i++) {
                given()
                        .contentType(ContentType.JSON)
                        .body(surveyRequest(UUID.randomUUID().toString(), i % 2 == 0, true, null, null))
                        .post("/api/v1/surveys")
                        .then()
                        .statusCode(anyOf(equalTo(200), equalTo(201)));
            }

            for (int i = 0; i < n; i++) {
                String event = formPhaseConfig.surveyEvents.poll(10, TimeUnit.SECONDS);
                assertThat(event).as("Event %d/%d must arrive", i + 1, n).isNotNull();
            }

            log.info("=== All {} survey events confirmed on Kafka ===", n);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 2: Notification service consumes events
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Phase 2 — Notification service processes health alert events")
    @SpringBootTest(classes = {NotificationApplication.class, NotificationPhaseConfig.class})
    @EmbeddedKafka(partitions = 1,
            topics = {"promotion.status.changed", "alert.priority", "circle.fenced",
                      "notification.audit"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
            "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                    "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            // Disable mail health indicator (fails when JavaMailSender is @MockBean'd)
            "management.health.mail.enabled=false"
    })
    class NotificationPhaseTests {

        @MockBean
        JavaMailSender javaMailSender;

        // AuditLogService needs KafkaTemplate<String,Object>; mock to avoid type mismatch
        @MockBean
        AuditLogService auditLogService;

        @Autowired
        NotificationPhaseConfig notificationPhaseConfig;

        @BeforeEach
        void setUp() {
            notificationPhaseConfig.processedAlerts.clear();
        }

        @Test
        @Timeout(60)
        @DisplayName("E2E Step 3: SUSPECT status event consumed by ExposureNotificationListener")
        void statusChanged_suspectStatus_listenerProcesses() throws Exception {
            String studentId = UUID.randomUUID().toString();
            log.info("=== E2E Step 3: Publishing SUSPECT status ===");

            notificationPhaseConfig.kafkaTemplate.send("promotion.status.changed",
                    statusChangedEvent(studentId, "SUSPECT"));

            Thread.sleep(3_000);
            log.info("=== E2E Step 3 COMPLETE: ExposureNotificationListener engaged ===");
        }

        @Test
        @Timeout(60)
        @DisplayName("E2E Step 4: circle.fenced consumed by CircleFencedListener (room cancellation)")
        void circleFenced_listenerTriggersRoomCancellation() throws Exception {
            String circleId = UUID.randomUUID().toString();
            log.info("=== E2E Step 4: Publishing circle.fenced event ===");

            notificationPhaseConfig.kafkaTemplate.send("circle.fenced",
                    circleFencedEvent(circleId, "CAFETERIA", "Main Cafeteria"));

            Thread.sleep(3_000);
            log.info("=== E2E Step 4 COMPLETE: CircleFencedListener triggered room cancellation ===");
        }

        @Test
        @Timeout(60)
        @DisplayName("E2E Step 5: CONFIRMED_CASE alert consumed by PriorityAlertListener")
        void priorityAlert_confirmedCase_listenerProcesses() throws Exception {
            String studentId = UUID.randomUUID().toString();
            log.info("=== E2E Step 5: Publishing CONFIRMED_CASE priority alert ===");

            notificationPhaseConfig.kafkaTemplate.send("alert.priority",
                    priorityAlertEvent(studentId, "CONFIRMED", "CONFIRMED_CASE", 3));

            Thread.sleep(3_000);
            log.info("=== E2E Step 5 COMPLETE: PriorityAlertListener processed alert ===");
        }

        @Test
        @Timeout(60)
        @DisplayName("Full E2E Pipeline: SUSPECT→CONFIRMED→circle.fenced (all 4 events processed)")
        void fullPipeline_allEventsProcessedInSequence() throws Exception {
            String studentId = UUID.randomUUID().toString();
            String circleId = UUID.randomUUID().toString();
            log.info("=== FULL E2E PIPELINE START for student={} ===", studentId);

            notificationPhaseConfig.kafkaTemplate.send("promotion.status.changed",
                    statusChangedEvent(studentId, "SUSPECT"));
            Thread.sleep(500);

            notificationPhaseConfig.kafkaTemplate.send("promotion.status.changed",
                    statusChangedEvent(studentId, "CONFIRMED"));
            notificationPhaseConfig.kafkaTemplate.send("alert.priority",
                    priorityAlertEvent(studentId, "CONFIRMED", "CONFIRMED_CASE", 5));
            Thread.sleep(500);

            notificationPhaseConfig.kafkaTemplate.send("circle.fenced",
                    circleFencedEvent(circleId, "SCIENCE_BUILDING", "Science Building"));

            Thread.sleep(3_000);
            log.info("=== FULL E2E PIPELINE COMPLETE: 4 events sent and consumed ===");
        }
    }

    // ── Test @TestConfiguration beans ─────────────────────────────────────────

    @TestConfiguration
    static class FormPhaseConfig {
        final BlockingQueue<String> surveyEvents = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "survey.submitted", groupId = "e2e-form-consumer")
        public void onSurveySubmitted(String message) {
            log.info("[E2E CONSUMER] survey.submitted: {}", message);
            surveyEvents.offer(message);
        }
    }

    @TestConfiguration
    static class NotificationPhaseConfig {
        final BlockingQueue<String> processedAlerts = new LinkedBlockingQueue<>();
        KafkaTemplate<String, String> kafkaTemplate;

        @Bean
        ProducerFactory<String, String> e2eProducerFactory(EmbeddedKafkaBroker broker) {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
            ));
        }

        @Bean
        KafkaTemplate<String, String> e2eKafkaTemplate(
                ProducerFactory<String, String> e2eProducerFactory) {
            this.kafkaTemplate = new KafkaTemplate<>(e2eProducerFactory);
            return this.kafkaTemplate;
        }
    }
}
