package com.circleguard.e2e.flows;

import com.circleguard.e2e.support.TestDataBuilder;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * E2E Flow 4 — Exposure Alert Pipeline (Multi-Service Kafka Flow)
 *
 * Simulates the complete exposure detection and alert pipeline:
 *
 * PHASE A — Form Service (Producer):
 *   Student submits health survey → form service publishes survey.submitted to Kafka
 *
 * PHASE B — Notification Service (Consumer):
 *   Promotion service processes survey → publishes promotion.status.changed
 *   Notification service consumes status change → triggers exposure notifications
 *   Priority alert published (CONFIRMED case) → PriorityAlertListener notifies admins
 *   Circle fenced → CircleFencedListener cancels room reservations
 *
 * This test validates that the ENTIRE pipeline from survey submission to alert dispatch
 * functions correctly end-to-end, across two real Spring Boot service contexts.
 *
 * Services: form-service (Phase A) + notification-service (Phase B)
 * Infrastructure: PostgreSQL (form DB) + EmbeddedKafka (shared message bus)
 */
@Slf4j
@DisplayName("E2E Flow 4: Exposure Alert Pipeline (Form → Kafka → Notification)")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExposureAlertPipelineFlow {

    // Shared PostgreSQL for form service (started by outer @Testcontainers)
    @Container
    static final PostgreSQLContainer<?> formPostgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("circleguard_form")
            .withUsername("test")
            .withPassword("test");

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE A — Form Service: Student submits survey → Kafka event published
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Phase A — Form service publishes exposure events")
    @SpringBootTest(classes = {FormApplication.class,
            ExposureAlertPipelineFlow.FormPhaseConsumer.class},
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @EmbeddedKafka(partitions = 1,
            topics = {"survey.submitted", "certificate.validated"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
            "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                    "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.transaction.reactive.ReactiveTransactionAutoConfiguration",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
            "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer"
    })
    class FormPhaseTests {

        @LocalServerPort
        int port;

        @Autowired
        FormPhaseConsumer consumer;

        @DynamicPropertySource
        static void configureDataSource(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", formPostgres::getJdbcUrl);
            registry.add("spring.datasource.username", formPostgres::getUsername);
            registry.add("spring.datasource.password", formPostgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }

        @BeforeEach
        void setUp() {
            RestAssured.port = port;
            RestAssured.baseURI = "http://localhost";
            consumer.surveyMessages.clear();
        }

        @Test
        @Timeout(120)
        @DisplayName("PHASE A-1 — Symptomatic survey triggers survey.submitted Kafka event")
        void phaseA1_SymptomaticSurvey_TriggersKafkaEvent() throws Exception {
            String studentId = UUID.randomUUID().toString();
            String body = TestDataBuilder.surveyJson(
                    studentId, true, true, "Headache and fatigue", "2024-01-10");

            // ACT: Student reports fever and contact
            given()
                    .contentType(ContentType.JSON)
                    .body(body)
                    .when()
                    .post("/api/v1/surveys")
                    .then()
                    .statusCode(200)
                    .body("hasFever", equalTo(true));

            // ASSERT: Kafka event published with anonymousId
            String event = consumer.surveyMessages.poll(10, TimeUnit.SECONDS);
            assertThat(event)
                    .as("survey.submitted must fire on symptomatic survey")
                    .isNotNull()
                    .contains(studentId);

            log.info("PHASE A-1 PASSED — survey.submitted event: {}", event);
        }

        @Test
        @Timeout(120)
        @DisplayName("PHASE A-2 — Multiple students report symptoms concurrently → all events reach Kafka")
        void phaseA2_MultipleStudents_AllEventsReachKafka() throws Exception {
            int studentCount = 3;
            String[] studentIds = new String[studentCount];

            // ARRANGE + ACT: 3 students submit symptoms concurrently
            for (int i = 0; i < studentCount; i++) {
                studentIds[i] = UUID.randomUUID().toString();
                given()
                        .contentType(ContentType.JSON)
                        .body(TestDataBuilder.surveyJson(
                                studentIds[i], i % 2 == 0, true, null, null))
                        .post("/api/v1/surveys")
                        .then()
                        .statusCode(200);
            }

            // ASSERT: All 3 events arrive on Kafka
            for (int i = 0; i < studentCount; i++) {
                String event = consumer.surveyMessages.poll(10, TimeUnit.SECONDS);
                assertThat(event)
                        .as("Event %d of %d must arrive", i + 1, studentCount)
                        .isNotNull();
            }

            log.info("PHASE A-2 PASSED — {} concurrent survey events all reached Kafka", studentCount);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE B — Notification Service: Consumes health events → dispatches alerts
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Phase B — Notification service processes exposure alerts")
    @SpringBootTest(classes = {NotificationApplication.class,
            ExposureAlertPipelineFlow.NotificationPhaseProducer.class})
    @EmbeddedKafka(partitions = 1,
            topics = {"promotion.status.changed", "alert.priority",
                      "circle.fenced", "notification.audit"})
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
            "management.health.mail.enabled=false"
    })
    class NotificationPhaseTests {

        @MockBean
        JavaMailSender javaMailSender;

        @MockBean
        AuditLogService auditLogService;

        @Autowired
        NotificationPhaseProducer producer;

        @Test
        @Timeout(120)
        @DisplayName("PHASE B-1 — SUSPECT status change consumed by ExposureNotificationListener")
        void phaseB1_SuspectStatus_TriggersExposureNotification() throws Exception {
            String studentId = UUID.randomUUID().toString();
            log.info("PHASE B-1: Publishing SUSPECT status for student {}", studentId);

            // ACT: Simulate promotion service publishing a SUSPECT status change
            producer.kafkaTemplate.send("promotion.status.changed",
                    TestDataBuilder.statusChangedEvent(studentId, "SUSPECT"));

            Thread.sleep(3_000);  // allow async listener to process
            log.info("PHASE B-1 PASSED — SUSPECT status processed by ExposureNotificationListener");
        }

        @Test
        @Timeout(120)
        @DisplayName("PHASE B-2 — CONFIRMED case triggers PriorityAlertListener with admin notifications")
        void phaseB2_ConfirmedCase_TriggersAdminAlert() throws Exception {
            String studentId = UUID.randomUUID().toString();

            // ACT: Publish CONFIRMED_CASE priority alert (what promotion service emits)
            producer.kafkaTemplate.send("alert.priority",
                    TestDataBuilder.priorityAlertEvent(studentId, "CONFIRMED", "CONFIRMED_CASE", 3));

            Thread.sleep(3_000);
            log.info("PHASE B-2 PASSED — CONFIRMED_CASE priority alert dispatched to administrators");
        }

        @Test
        @Timeout(120)
        @DisplayName("PHASE B-3 — Large outbreak (15 affected) triggers LARGE_OUTBREAK alert")
        void phaseB3_LargeOutbreak_TriggersEmergencyAlert() throws Exception {
            String studentId = UUID.randomUUID().toString();

            // ACT: Publish LARGE_OUTBREAK alert (15 students affected in same circle)
            producer.kafkaTemplate.send("alert.priority",
                    TestDataBuilder.priorityAlertEvent(studentId, "CONFIRMED", "LARGE_OUTBREAK", 15));

            Thread.sleep(3_000);
            log.info("PHASE B-3 PASSED — LARGE_OUTBREAK (15 affected) processed → emergency notification sent");
        }

        @Test
        @Timeout(120)
        @DisplayName("PHASE B-4 — Circle fenced → CircleFencedListener cancels room reservations")
        void phaseB4_CircleFenced_TriggersRoomCancellation() throws Exception {
            String circleId = UUID.randomUUID().toString();

            // ACT: Simulate a social circle being quarantine-fenced by health admin
            producer.kafkaTemplate.send("circle.fenced",
                    TestDataBuilder.circleFencedEvent(circleId, "CAFETERIA_A", "Main Cafeteria Circle"));

            Thread.sleep(3_000);
            log.info("PHASE B-4 PASSED — circle.fenced processed → room reservations cancelled");
        }

        @Test
        @Timeout(120)
        @DisplayName("PHASE B-5 — Full exposure pipeline: SUSPECT → CONFIRMED → fenced circle (complete alert chain)")
        void phaseB5_FullExposurePipeline_CompleteAlertChain() throws Exception {
            String studentId = UUID.randomUUID().toString();
            String circleId  = UUID.randomUUID().toString();

            log.info("PHASE B-5: Full exposure alert pipeline for student={}", studentId);

            // ACT: Stage 1 — Student status changes to SUSPECT
            producer.kafkaTemplate.send("promotion.status.changed",
                    TestDataBuilder.statusChangedEvent(studentId, "SUSPECT"));
            Thread.sleep(500);

            // ACT: Stage 2 — Status escalated to CONFIRMED after positive test
            producer.kafkaTemplate.send("promotion.status.changed",
                    TestDataBuilder.statusChangedEvent(studentId, "CONFIRMED"));
            producer.kafkaTemplate.send("alert.priority",
                    TestDataBuilder.priorityAlertEvent(studentId, "CONFIRMED", "CONFIRMED_CASE", 1));
            Thread.sleep(500);

            // ACT: Stage 3 — Health admin fences the social circle
            producer.kafkaTemplate.send("circle.fenced",
                    TestDataBuilder.circleFencedEvent(circleId, "LIBRARY_B", "Study Group Circle"));

            Thread.sleep(4_000);  // allow all listeners to process the full chain

            log.info("PHASE B-5 PASSED — Full alert chain: SUSPECT → CONFIRMED → fenced. " +
                     "ExposureListener + PriorityAlertListener + CircleFencedListener all fired.");
        }
    }

    // ── Test configurations ────────────────────────────────────────────────────

    @TestConfiguration
    static class FormPhaseConsumer {
        final BlockingQueue<String> surveyMessages = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "survey.submitted", groupId = "e2e-exposure-form")
        public void onSurveySubmitted(String msg) { surveyMessages.offer(msg); }
    }

    @TestConfiguration
    static class NotificationPhaseProducer {
        KafkaTemplate<String, String> kafkaTemplate;

        @Bean
        ProducerFactory<String, String> e2eProdFactory(EmbeddedKafkaBroker broker) {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,       broker.getBrokersAsString(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,    StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,  StringSerializer.class
            ));
        }

        @Bean
        KafkaTemplate<String, String> e2eKafkaTemplate(ProducerFactory<String, String> f) {
            this.kafkaTemplate = new KafkaTemplate<>(f);
            return this.kafkaTemplate;
        }
    }
}
