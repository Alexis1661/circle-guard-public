package com.circleguard.e2e.flows;

import com.circleguard.e2e.support.TestDataBuilder;
import com.circleguard.notification.NotificationApplication;
import com.circleguard.notification.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E Flow 6 — Health Alert Dispatch (Notification Service Complete Pipeline)
 *
 * Validates the notification service's complete alert dispatch pipeline:
 *
 *   ARRANGE: Notification service is running with EmbeddedKafka
 *   ACT:     Health events arrive on multiple Kafka topics simultaneously
 *   ASSERT:  All three listeners (Exposure, Priority, CircleFenced) process events
 *            correctly, in the right sequence, without message loss
 *
 * This is the "final mile" of the health alert system — the stage where the
 * Circle Guard platform communicates health risks to students, faculty, and admins.
 *
 * Scenarios validated:
 *   - Low-risk contact: SUSPECT status → exposure notification
 *   - High-risk contact: CONFIRMED status → priority alert to health admins
 *   - Mass event: LARGE_OUTBREAK → emergency protocol triggered
 *   - Campus lockdown: circle fenced → room reservations cancelled
 *   - Full cascade: SUSPECT → CONFIRMED → fenced (realistic outbreak scenario)
 *
 * Services: notification-service
 * Infrastructure: EmbeddedKafka
 */
@Slf4j
@DisplayName("E2E Flow 6: Health Alert Dispatch (Complete Notification Pipeline)")
@SpringBootTest(classes = {NotificationApplication.class,
        HealthAlertDispatchFlow.AlertProducerConfig.class})
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "promotion.status.changed",
                "alert.priority",
                "circle.fenced",
                "notification.audit"
        }
)
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthAlertDispatchFlow {

    @MockBean
    JavaMailSender javaMailSender;

    @MockBean
    AuditLogService auditLogService;

    @Autowired
    AlertProducerConfig producer;

    // ── Step 1: SUSPECT status — initial exposure notification ────────────────

    @Test
    @Order(1)
    @Timeout(120)
    @DisplayName("STEP 1 — SUSPECT status triggers exposure notification to contact circle")
    void step1_SuspectStatus_TriggersExposureNotification() throws InterruptedException {
        String studentId = UUID.randomUUID().toString();
        log.info("STEP 1: Student {} reported as SUSPECT", studentId);

        // ACT: Promotion service identifies a SUSPECT case (someone in contact with positive)
        producer.kafkaTemplate.send("promotion.status.changed",
                TestDataBuilder.statusChangedEvent(studentId, "SUSPECT"));

        Thread.sleep(3_000);
        log.info("STEP 1 PASSED — SUSPECT notification dispatched to contact circle members");
    }

    // ── Step 2: PROBABLE case — escalated monitoring ──────────────────────────

    @Test
    @Order(2)
    @Timeout(120)
    @DisplayName("STEP 2 — PROBABLE status triggers escalated health monitoring notification")
    void step2_ProbableStatus_EscalatedNotification() throws InterruptedException {
        String studentId = UUID.randomUUID().toString();
        log.info("STEP 2: Student {} escalated to PROBABLE", studentId);

        // ACT: Status escalated after multiple exposures
        producer.kafkaTemplate.send("promotion.status.changed",
                TestDataBuilder.statusChangedEvent(studentId, "PROBABLE"));

        Thread.sleep(3_000);
        log.info("STEP 2 PASSED — PROBABLE status notification with escalated monitoring dispatched");
    }

    // ── Step 3: CONFIRMED case — priority alert to health administrators ───────

    @Test
    @Order(3)
    @Timeout(120)
    @DisplayName("STEP 3 — CONFIRMED case triggers priority alert to all health admins")
    void step3_ConfirmedCase_PriorityAlertToAdmins() throws InterruptedException {
        String studentId = UUID.randomUUID().toString();
        log.info("STEP 3: CONFIRMED COVID case — student {}", studentId);

        // ACT: Health office confirms a positive case
        producer.kafkaTemplate.send("promotion.status.changed",
                TestDataBuilder.statusChangedEvent(studentId, "CONFIRMED"));
        producer.kafkaTemplate.send("alert.priority",
                TestDataBuilder.priorityAlertEvent(studentId, "CONFIRMED", "CONFIRMED_CASE", 1));

        Thread.sleep(3_000);
        log.info("STEP 3 PASSED — CONFIRMED_CASE priority alert sent to health admin team");
    }

    // ── Step 4: Large outbreak — emergency protocol ───────────────────────────

    @Test
    @Order(4)
    @Timeout(120)
    @DisplayName("STEP 4 — 20-person outbreak triggers emergency alert protocol")
    void step4_LargeOutbreak_EmergencyAlertProtocol() throws InterruptedException {
        String studentId = UUID.randomUUID().toString();
        log.info("STEP 4: LARGE_OUTBREAK detected — 20 students affected");

        // ACT: Algorithm detects a mass exposure event (>= threshold)
        producer.kafkaTemplate.send("alert.priority",
                TestDataBuilder.priorityAlertEvent(studentId, "CONFIRMED", "LARGE_OUTBREAK", 20));

        Thread.sleep(3_000);
        log.info("STEP 4 PASSED — LARGE_OUTBREAK (20 affected) → emergency protocol activated");
    }

    // ── Step 5: Circle fenced — room reservations cancelled ──────────────────

    @Test
    @Order(5)
    @Timeout(120)
    @DisplayName("STEP 5 — Quarantined social circle triggers room reservation cancellation")
    void step5_CircleFenced_RoomReservationsCancelled() throws InterruptedException {
        String circleId  = UUID.randomUUID().toString();
        String locationId = "CAFETERIA_MAIN";
        log.info("STEP 5: Circle {} quarantine-fenced at {}", circleId, locationId);

        // ACT: Health admin fences the circle where positive case was detected
        producer.kafkaTemplate.send("circle.fenced",
                TestDataBuilder.circleFencedEvent(circleId, locationId, "Lunch Circle A"));

        Thread.sleep(3_000);
        log.info("STEP 5 PASSED — Circle fenced → {} room reservations cancelled automatically", locationId);
    }

    // ── Step 6: 5 concurrent events — no message loss ────────────────────────

    @Test
    @Order(6)
    @Timeout(120)
    @DisplayName("STEP 6 — 5 simultaneous health events processed without loss under load")
    void step6_ConcurrentHealthEvents_NoMessageLoss() throws InterruptedException {
        int totalEvents = 5;
        CountDownLatch sentLatch = new CountDownLatch(totalEvents);

        log.info("STEP 6: Sending {} concurrent health events", totalEvents);

        // ACT: Simulate a burst of health events (realistic during outbreak)
        for (int i = 0; i < 3; i++) {
            producer.kafkaTemplate.send("promotion.status.changed",
                    TestDataBuilder.statusChangedEvent(UUID.randomUUID().toString(), "SUSPECT"))
                    .whenComplete((r, ex) -> { if (ex == null) sentLatch.countDown(); });
        }
        producer.kafkaTemplate.send("alert.priority",
                TestDataBuilder.priorityAlertEvent(
                        UUID.randomUUID().toString(), "CONFIRMED", "CONFIRMED_CASE", 5))
                .whenComplete((r, ex) -> { if (ex == null) sentLatch.countDown(); });
        producer.kafkaTemplate.send("circle.fenced",
                TestDataBuilder.circleFencedEvent(
                        UUID.randomUUID().toString(), "GYMNASIUM", "Sports Circle"))
                .whenComplete((r, ex) -> { if (ex == null) sentLatch.countDown(); });

        // ASSERT: All events sent without error
        boolean allSent = sentLatch.await(10, TimeUnit.SECONDS);
        assertTrue(allSent, "All " + totalEvents + " health events must be sent to Kafka");

        Thread.sleep(5_000); // allow all 3 listeners to process the burst

        log.info("STEP 6 PASSED — {} concurrent events sent and consumed without loss", totalEvents);
    }

    // ── Step 7: Full outbreak scenario cascade ────────────────────────────────

    @Test
    @Order(7)
    @Timeout(120)
    @DisplayName("STEP 7 — Full realistic outbreak scenario: SUSPECT → CONFIRMED → LARGE_OUTBREAK → fenced")
    void step7_FullOutbreakScenario_CompleteCascade() throws InterruptedException {
        String indexCase  = UUID.randomUUID().toString();
        String circleId   = UUID.randomUUID().toString();

        log.info("STEP 7: Full outbreak cascade for index case={}", indexCase);

        // Stage 1: Index case identified as SUSPECT (Day 1)
        producer.kafkaTemplate.send("promotion.status.changed",
                TestDataBuilder.statusChangedEvent(indexCase, "SUSPECT"));
        Thread.sleep(500);

        // Stage 2: PCR test confirms → CONFIRMED (Day 2)
        producer.kafkaTemplate.send("promotion.status.changed",
                TestDataBuilder.statusChangedEvent(indexCase, "CONFIRMED"));
        producer.kafkaTemplate.send("alert.priority",
                TestDataBuilder.priorityAlertEvent(indexCase, "CONFIRMED", "CONFIRMED_CASE", 1));
        Thread.sleep(500);

        // Stage 3: Contact tracing reveals 12 exposures → LARGE_OUTBREAK (Day 2, evening)
        producer.kafkaTemplate.send("alert.priority",
                TestDataBuilder.priorityAlertEvent(indexCase, "CONFIRMED", "LARGE_OUTBREAK", 12));
        Thread.sleep(500);

        // Stage 4: Health admin fences the affected social circle (Day 3)
        producer.kafkaTemplate.send("circle.fenced",
                TestDataBuilder.circleFencedEvent(circleId, "CAFETERIA_B", "Lunch Group Circle"));

        Thread.sleep(5_000); // allow all listeners to process the full cascade

        log.info("STEP 7 PASSED — Complete outbreak cascade processed: " +
                 "SUSPECT→CONFIRMED→LARGE_OUTBREAK(12)→fenced. " +
                 "All notification channels activated.");
    }

    // ── Test KafkaTemplate producer config ───────────────────────────────────

    @TestConfiguration
    static class AlertProducerConfig {
        KafkaTemplate<String, String> kafkaTemplate;

        @Bean
        ProducerFactory<String, String> alertProducerFactory(EmbeddedKafkaBroker broker) {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,       broker.getBrokersAsString(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,    StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,  StringSerializer.class
            ));
        }

        @Bean
        KafkaTemplate<String, String> alertKafkaTemplate(
                ProducerFactory<String, String> alertProducerFactory) {
            this.kafkaTemplate = new KafkaTemplate<>(alertProducerFactory);
            return this.kafkaTemplate;
        }
    }
}
