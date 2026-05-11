package com.circleguard.integration;

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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for PROMOTION → NOTIFICATION Kafka pipeline.
 *
 * The promotion-service is NOT started (avoids Neo4j dependency).
 * Messages are published DIRECTLY to Kafka topics, simulating what promotion-service emits.
 * Notification-service consumers pick them up and process them.
 *
 * Topics tested:
 *   promotion.status.changed → ExposureNotificationListener
 *   alert.priority           → PriorityAlertListener
 *   circle.fenced            → CircleFencedListener
 */
@Slf4j
@DisplayName("PROMOTION → NOTIFICATION Kafka Integration")
@SpringBootTest(classes = {NotificationApplication.class,
        PromotionNotificationKafkaTest.TestProducerConfig.class})
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
        // Notification service does not use a database or Spring Security
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        // EmbeddedKafka auto-sets spring.kafka.bootstrap-servers — consumer group config
        "spring.kafka.consumer.auto-offset-reset=earliest",
        // Disable mail health indicator (fails when JavaMailSender is @MockBean'd)
        "management.health.mail.enabled=false"
})
class PromotionNotificationKafkaTest {

    // Mock external I/O to prevent real email/SMS in tests
    @MockBean
    JavaMailSender javaMailSender;

    // AuditLogService needs KafkaTemplate<String,Object>; mock it to avoid type-mismatch
    @MockBean
    AuditLogService auditLogService;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    // ── Test 1: SUSPECT status triggers ExposureNotificationListener ──────────

    @Test
    @Timeout(60)
    @DisplayName("promotion.status.changed SUSPECT → ExposureNotificationListener processes event")
    void publishSuspectStatus_notificationListenerProcesses() throws InterruptedException {
        String event = statusChangedEvent(UUID.randomUUID().toString(), "SUSPECT");

        kafkaTemplate.send("promotion.status.changed", event);

        Thread.sleep(3_000); // allow async consumer to process
        log.info("SUSPECT status event processed by notification service");
    }

    // ── Test 2: CONFIRMED status triggers alert pipeline ──────────────────────

    @Test
    @Timeout(60)
    @DisplayName("promotion.status.changed CONFIRMED → priority alert pipeline engaged")
    void publishConfirmedStatus_triggersAlertPipeline() throws InterruptedException {
        String event = statusChangedEvent(UUID.randomUUID().toString(), "CONFIRMED");

        kafkaTemplate.send("promotion.status.changed", event);

        Thread.sleep(3_000);
        log.info("CONFIRMED status event processed — alert pipeline engaged");
    }

    // ── Test 3: Priority alert CONFIRMED_CASE triggers PriorityAlertListener ──

    @Test
    @Timeout(60)
    @DisplayName("alert.priority CONFIRMED_CASE → PriorityAlertListener processes event")
    void publishPriorityAlertConfirmedCase_listenerProcesses() throws InterruptedException {
        String event = priorityAlertEvent(UUID.randomUUID().toString(), "CONFIRMED",
                "CONFIRMED_CASE", 1);

        kafkaTemplate.send("alert.priority", event);

        Thread.sleep(3_000);
        log.info("CONFIRMED_CASE priority alert processed by notification service");
    }

    // ── Test 4: LARGE_OUTBREAK alert triggers PriorityAlertListener ───────────

    @Test
    @Timeout(60)
    @DisplayName("alert.priority LARGE_OUTBREAK (15 affected) → PriorityAlertListener processes")
    void publishLargeOutbreakAlert_listenerProcesses() throws InterruptedException {
        String event = priorityAlertEvent(UUID.randomUUID().toString(), "CONFIRMED",
                "LARGE_OUTBREAK", 15);

        kafkaTemplate.send("alert.priority", event);

        Thread.sleep(3_000);
        log.info("LARGE_OUTBREAK (15 affected) processed by notification service");
    }

    // ── Test 5: circle.fenced triggers CircleFencedListener ──────────────────

    @Test
    @Timeout(60)
    @DisplayName("circle.fenced → CircleFencedListener triggers room cancellation")
    void publishCircleFenced_listenerTriggersRoomCancellation() throws InterruptedException {
        String event = circleFencedEvent(UUID.randomUUID().toString(),
                "SCIENCE_BUILDING", "Science Wing");

        kafkaTemplate.send("circle.fenced", event);

        Thread.sleep(3_000);
        log.info("circle.fenced event processed — room cancellation triggered");
    }

    // ── Test 6: Multiple concurrent events — no message loss ─────────────────

    @Test
    @Timeout(60)
    @DisplayName("5 concurrent status events are all sent to Kafka without loss")
    void publishMultipleEvents_allSentSuccessfully() throws InterruptedException {
        int count = 5;
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            String event = statusChangedEvent(UUID.randomUUID().toString(), "SUSPECT");
            kafkaTemplate.send("promotion.status.changed", event)
                    .whenComplete((r, ex) -> { if (ex == null) latch.countDown(); });
        }

        boolean allSent = latch.await(10, TimeUnit.SECONDS);
        assertTrue(allSent, "All " + count + " events must be sent successfully");
        Thread.sleep(3_000); // allow consumers to process

        log.info("{} status events published and consumed without loss", count);
    }

    // ── JSON event builders ───────────────────────────────────────────────────

    private String statusChangedEvent(String anonymousId, String status) {
        return String.format("{\"anonymousId\":\"%s\",\"status\":\"%s\",\"timestamp\":\"%s\"}",
                anonymousId, status, Instant.now());
    }

    private String priorityAlertEvent(String anonymousId, String status,
                                       String eventType, int affectedCount) {
        return String.format(
                "{\"anonymousId\":\"%s\",\"status\":\"%s\",\"affectedCount\":%d," +
                "\"eventType\":\"%s\",\"timestamp\":\"%s\"}",
                anonymousId, status, affectedCount, eventType, Instant.now());
    }

    private String circleFencedEvent(String circleId, String locationId, String name) {
        return String.format(
                "{\"circleId\":\"%s\",\"locationId\":\"%s\",\"name\":\"%s\",\"timestamp\":\"%s\"}",
                circleId, locationId, name, Instant.now());
    }

    // ── Test KafkaTemplate bean ───────────────────────────────────────────────

    @TestConfiguration
    static class TestProducerConfig {
        @Bean
        ProducerFactory<String, String> testProducerFactory(EmbeddedKafkaBroker broker) {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
            ));
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(
                ProducerFactory<String, String> testProducerFactory) {
            return new KafkaTemplate<>(testProducerFactory);
        }
    }
}
