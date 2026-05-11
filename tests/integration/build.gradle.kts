import java.time.Duration

// Integration test module — tests REAL communication between microservices
// Uses TestContainers (PostgreSQL, Kafka, Redis) + RestAssured + EmbeddedKafka

plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.4")
    }
}

dependencies {
    // ── Service modules under test ─────────────────────────────────────────
    testImplementation(project(":services:circleguard-auth-service"))
    testImplementation(project(":services:circleguard-identity-service"))
    testImplementation(project(":services:circleguard-gateway-service"))
    testImplementation(project(":services:circleguard-form-service"))
    testImplementation(project(":services:circleguard-notification-service"))
    // promotion-service intentionally excluded: Neo4j dependency causes classpath issues
    // Promotion→Notification Kafka flow is tested by publishing directly to Kafka topics

    // ── TestContainers ─────────────────────────────────────────────────────
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:kafka:1.19.7")

    // ── HTTP Testing ───────────────────────────────────────────────────────
    testImplementation("io.rest-assured:rest-assured:5.3.2")

    // ── Async assertions ───────────────────────────────────────────────────
    testImplementation("org.awaitility:awaitility:4.2.0")

    // ── Kafka (core + testing) ─────────────────────────────────────────────
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")

    // ── Mail (for @MockBean JavaMailSender) ────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-mail")

    // ── HTTP Service Mocking ───────────────────────────────────────────────
    testImplementation("org.wiremock:wiremock-standalone:3.3.1")

    // ── JWT (for generating test tokens inline) ────────────────────────────
    testImplementation("io.jsonwebtoken:jjwt-api:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")

    // ── Database ───────────────────────────────────────────────────────────
    testRuntimeOnly("org.postgresql:postgresql")

    // ── Spring Security test support ───────────────────────────────────────
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    timeout.set(Duration.ofMinutes(10))
    // propagate system property for profile selection
    systemProperty("spring.profiles.active", "test")
}
