import java.time.Duration

// E2E test module — validates COMPLETE USER FLOWS across multiple microservices.
// Tests in this module are heavier than integration tests: they boot real service
// contexts, spin up full infrastructure via TestContainers, and simulate end-to-end
// user journeys (login → act → assert downstream effects).

plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.2.4")
    }
}

dependencies {
    // ── Service modules ────────────────────────────────────────────────────
    testImplementation(project(":services:circleguard-auth-service"))
    testImplementation(project(":services:circleguard-identity-service"))
    testImplementation(project(":services:circleguard-gateway-service"))
    testImplementation(project(":services:circleguard-form-service"))
    testImplementation(project(":services:circleguard-notification-service"))
    testImplementation(project(":services:circleguard-promotion-service"))

    // ── TestContainers ─────────────────────────────────────────────────────
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:kafka:1.19.7")
    testImplementation("org.testcontainers:neo4j:1.19.7")

    // ── HTTP Testing ───────────────────────────────────────────────────────
    testImplementation("io.rest-assured:rest-assured:5.3.2")

    // ── Async Testing ──────────────────────────────────────────────────────
    testImplementation("org.awaitility:awaitility:4.2.0")

    // ── Kafka Testing ──────────────────────────────────────────────────────
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")

    // ── HTTP Service Mocking ───────────────────────────────────────────────
    testImplementation("org.wiremock:wiremock-standalone:3.3.1")

    // ── Mail (for @MockBean JavaMailSender) ────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-mail")

    // ── JWT (for generating admin test tokens) ─────────────────────────────
    testImplementation("io.jsonwebtoken:jjwt-api:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")

    // ── Database ───────────────────────────────────────────────────────────
    testRuntimeOnly("org.postgresql:postgresql")

    // ── Security test support ──────────────────────────────────────────────
    testImplementation("org.springframework.security:spring-security-test")

    // ── Spring TX (for @Primary TransactionManager in CampusInfrastructureSetupFlow) ─
    testImplementation("org.springframework:spring-tx")

    // ── Spring Data Neo4j + Driver (for @MockBean in CampusInfrastructureSetupFlow) ─
    testImplementation("org.springframework.data:spring-data-neo4j")
    testImplementation("org.neo4j.driver:neo4j-java-driver")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    timeout.set(Duration.ofMinutes(30))
    systemProperty("spring.profiles.active", "e2e-test")
}
