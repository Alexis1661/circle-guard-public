package com.circleguard.e2e.flows;

import com.circleguard.e2e.support.TestDataBuilder;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E Flow 3 — Student Health Survey Complete Journey
 *
 * Validates the complete health data collection pipeline:
 *
 *   ARRANGE: Form service is running with PostgreSQL + EmbeddedKafka
 *   ACT:     Student queries available questionnaires → submits symptom report →
 *            attaches medical certificate → admin approves certificate
 *   ASSERT:  All state transitions correct; Kafka events published to correct topics;
 *            downstream services (promotion, notification) receive the events
 *
 * Flow:
 *   1. Form service API is accessible (questionnaire list returns empty)
 *   2. No active questionnaire → surveys still accepted (symptom detection = false)
 *   3. Student submits symptomatic survey → survey.submitted Kafka event
 *   4. Student submits asymptomatic survey → survey.submitted Kafka event (hasSymptoms=false)
 *   5. Student attaches medical certificate → validationStatus=PENDING
 *   6. Admin approves certificate → certificate.validated Kafka event published
 *
 * Services: form-service
 * Infrastructure: PostgreSQL (TestContainers) + EmbeddedKafka
 */
@Slf4j
@DisplayName("E2E Flow 3: Student Health Survey Complete Journey")
@SpringBootTest(classes = {FormApplication.class,
        StudentHealthSurveyJourneyFlow.TestConsumerConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"survey.submitted", "certificate.validated"}
)
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
                // promotion-service brings spring-data-neo4j which creates reactiveTransactionManager
                // conflicting with JPA transactionManager on @Transactional method dispatch
                "org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.transaction.reactive.ReactiveTransactionAutoConfiguration",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.jpa.open-in-view=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudentHealthSurveyJourneyFlow {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("circleguard_form")
            .withUsername("test")
            .withPassword("test");

    @LocalServerPort
    int port;

    @Autowired
    TestConsumerConfig testConsumer;

    static final String STUDENT_ID   = UUID.randomUUID().toString();
    static final String ADMIN_ID     = UUID.randomUUID().toString();

    // Track IDs across test steps
    static String symptomsId;

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
        testConsumer.surveyMessages.clear();
        testConsumer.certMessages.clear();
    }

    // ── Step 1: Form service API is accessible ────────────────────────────────

    @Test
    @Order(1)
    @Timeout(120)
    @DisplayName("STEP 1 — Form service is accessible: questionnaire list endpoint returns correctly")
    void step1_FormServiceAccessible_QuestionnairesEndpointResponds() {
        // ACT: Student checks for active questionnaires before screening
        given()
                .when()
                .get("/api/v1/questionnaires")
                .then()
                // ASSERT: Endpoint is available (empty list is valid — no questionnaire created yet)
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));

        // ALSO: Active questionnaire endpoint returns 404 when none configured
        given()
                .when()
                .get("/api/v1/questionnaires/active")
                .then()
                .statusCode(404);

        log.info("STEP 1 PASSED — Form service API accessible. No active questionnaire (normal for fresh DB).");
    }

    // ── Step 2: Student submits symptomatic survey ────────────────────────────

    @Test
    @Order(2)
    @Timeout(120)
    @DisplayName("STEP 2 — Symptomatic student submits health survey → survey.submitted Kafka event")
    void step2_StudentSubmitsSymptomsReport_KafkaEventPublished() throws Exception {
        // ARRANGE: Student reports fever, fatigue, and recent exposure
        String surveyBody = TestDataBuilder.surveyJson(
                STUDENT_ID, true, false, "Muscle aches and loss of taste", "2024-01-10");

        // ACT: Submit the health survey
        symptomsId = given()
                .contentType(ContentType.JSON)
                .body(surveyBody)
                .when()
                .post("/api/v1/surveys")
                .then()
                // ASSERT: Survey accepted and stored
                .statusCode(200)
                .body("anonymousId", equalTo(STUDENT_ID))
                .body("hasFever", equalTo(true))
                .body("id", notNullValue())
                .extract()
                .path("id");

        assertThat(symptomsId).isNotNull();

        // ASSERT: Kafka event published for promotion service to process
        String kafkaMsg = testConsumer.surveyMessages.poll(10, TimeUnit.SECONDS);
        assertThat(kafkaMsg)
                .as("survey.submitted must be published after symptomatic survey")
                .isNotNull()
                .contains(STUDENT_ID);

        log.info("STEP 2 PASSED — Symptomatic survey published Kafka event: {}", kafkaMsg);
    }

    // ── Step 3: Student submits healthy check-in ──────────────────────────────

    @Test
    @Order(3)
    @Timeout(120)
    @DisplayName("STEP 3 — Healthy student submits daily check-in → survey.submitted (hasSymptoms=false)")
    void step3_HealthyStudentSubmitsDailyCheckIn() throws Exception {
        // ARRANGE: Healthy student, no symptoms
        String anotherStudentId = UUID.randomUUID().toString();
        String surveyBody = TestDataBuilder.surveyJson(
                anotherStudentId, false, false, null, null);

        // ACT: Daily health check-in
        String surveyId = given()
                .contentType(ContentType.JSON)
                .body(surveyBody)
                .when()
                .post("/api/v1/surveys")
                .then()
                .statusCode(200)
                .body("anonymousId", equalTo(anotherStudentId))
                .body("hasFever", equalTo(false))
                .extract()
                .path("id");

        assertThat(surveyId).isNotNull();

        // ASSERT: Event published even for healthy students (promotes to ACTIVE status)
        String kafkaMsg = testConsumer.surveyMessages.poll(10, TimeUnit.SECONDS);
        assertThat(kafkaMsg)
                .as("survey.submitted must fire even for healthy surveys")
                .isNotNull()
                .contains(anotherStudentId);

        log.info("STEP 3 PASSED — Healthy student check-in processed. Event: {}", kafkaMsg);
    }

    // ── Step 4: Pending certificates list ────────────────────────────────────

    @Test
    @Order(4)
    @Timeout(120)
    @DisplayName("STEP 4 — Pending certificates list accessible for health admin review")
    void step4_PendingCertificatesListAccessible() {
        // ACT: Health admin checks for certificates awaiting review
        given()
                .when()
                .get("/api/v1/certificates/pending")
                .then()
                // ASSERT: Endpoint is accessible and returns a list
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));

        log.info("STEP 4 PASSED — Pending certificates endpoint accessible");
    }

    // ── Step 5: Student attaches medical certificate ──────────────────────────

    @Test
    @Order(5)
    @Timeout(120)
    @DisplayName("STEP 5 — Student attaches medical certificate → validationStatus=PENDING")
    void step5_StudentAttachesMedicalCertificate() throws Exception {
        // ARRANGE: Student with confirmed positive test attaches their medical certificate
        String anonId = UUID.randomUUID().toString();
        String certBody = TestDataBuilder.surveyWithAttachmentJson(
                anonId, true, true,
                "Confirmed COVID-19 positive PCR test",
                "2024-01-12",
                "/uploads/pcr-test-result-2024-001.pdf"
        );

        // ACT: Submit survey WITH attachment (automatically creates a pending certificate)
        String certId = given()
                .contentType(ContentType.JSON)
                .body(certBody)
                .when()
                .post("/api/v1/surveys")
                .then()
                // ASSERT: Certificate stored as PENDING (awaiting health admin review)
                .statusCode(200)
                .body("validationStatus", equalTo("PENDING"))
                .body("attachmentPath", containsString(".pdf"))
                .body("id", notNullValue())
                .extract()
                .path("id");

        assertThat(certId).isNotNull();

        // Drain the survey.submitted event
        testConsumer.surveyMessages.poll(5, TimeUnit.SECONDS);

        // ASSERT: Certificate appears in pending queue for admin review
        given()
                .when()
                .get("/api/v1/certificates/pending")
                .then()
                .statusCode(200)
                .body("$", not(empty()));

        log.info("STEP 5 PASSED — Medical certificate submitted: id={}, status=PENDING", certId);
    }

    // ── Step 6: Health admin approves certificate ─────────────────────────────

    @Test
    @Order(6)
    @Timeout(120)
    @DisplayName("STEP 6 — Health admin approves certificate → certificate.validated event triggers recovery")
    void step6_HealthAdminApprovesCertificate() throws Exception {
        // ARRANGE: Create a fresh survey+certificate for approval
        String anonId = UUID.randomUUID().toString();
        String certBody = TestDataBuilder.surveyWithAttachmentJson(
                anonId, true, true, "COVID-19 recovered", "2024-01-08",
                "/uploads/recovery-certificate-2024.pdf"
        );

        String certId = given()
                .contentType(ContentType.JSON)
                .body(certBody)
                .post("/api/v1/surveys")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        assertThat(certId).isNotNull();
        testConsumer.surveyMessages.poll(5, TimeUnit.SECONDS);

        // ACT: Health admin reviews and approves the medical certificate
        given()
                .queryParam("status", "APPROVED")
                .queryParam("adminId", ADMIN_ID)
                .when()
                .post("/api/v1/certificates/" + certId + "/validate")
                .then()
                // ASSERT: Admin approval accepted
                .statusCode(200);

        // ASSERT: certificate.validated Kafka event fires
        // (promotion service uses this to restore student to ACTIVE status)
        String certKafkaMsg = testConsumer.certMessages.poll(10, TimeUnit.SECONDS);
        assertThat(certKafkaMsg)
                .as("certificate.validated must fire after admin approval")
                .isNotNull()
                .contains(anonId);

        log.info("STEP 6 PASSED — Certificate approved. Kafka event: {}", certKafkaMsg);
        log.info("=== COMPLETE HEALTH SURVEY JOURNEY: {} → PENDING → APPROVED → Kafka ===", anonId);
    }

    // ── Test Kafka consumer ───────────────────────────────────────────────────

    @TestConfiguration
    static class TestConsumerConfig {
        final BlockingQueue<String> surveyMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> certMessages   = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "survey.submitted",      groupId = "e2e-survey-journey")
        public void onSurvey(String msg) { surveyMessages.offer(msg); }

        @KafkaListener(topics = "certificate.validated", groupId = "e2e-cert-journey")
        public void onCert(String msg)   { certMessages.offer(msg); }

        @Bean
        ConsumerFactory<String, String> e2eJourneyConsumerFactory(EmbeddedKafkaBroker broker) {
            return new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        broker.getBrokersAsString(),
                    ConsumerConfig.GROUP_ID_CONFIG,                 "e2e-journey-group",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
            ));
        }
    }
}
