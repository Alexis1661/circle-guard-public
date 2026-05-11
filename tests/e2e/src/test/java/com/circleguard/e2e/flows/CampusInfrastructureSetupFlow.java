package com.circleguard.e2e.flows;

import com.circleguard.e2e.support.TestDataBuilder;
import com.circleguard.promotion.PromotionApplication;
import com.circleguard.promotion.config.Neo4jSchemaConfig;
import com.circleguard.promotion.listener.SurveyListener;
import com.circleguard.promotion.repository.graph.CircleNodeRepository;
import com.circleguard.promotion.repository.graph.UserNodeRepository;
import com.circleguard.promotion.service.AutoCircleService;
import com.circleguard.promotion.service.CircleService;
import com.circleguard.promotion.service.GraphService;
import com.circleguard.promotion.service.HealthStatusService;
import com.circleguard.promotion.service.LocationResolutionService;
import com.circleguard.promotion.service.MacSessionRegistry;
import com.circleguard.promotion.service.SpatialService;
import com.circleguard.promotion.service.StatusLifecycleService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.neo4j.driver.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * E2E Flow 2 — Campus Infrastructure Setup
 *
 * Tests the IT admin journey of configuring the campus topology: buildings → floors → APs.
 * Each test method is INDEPENDENT (self-contained), avoiding shared state issues.
 *
 * The promotion-service has Neo4j + Redis + Kafka dependencies.
 * All Neo4j-dependent services are @MockBean'd so only JPA beans run.
 * JPA's own @Primary transactionManager handles @Transactional methods.
 *
 * Infrastructure: PostgreSQL (TestContainers) — no Neo4j/Redis/Kafka containers.
 */
@Slf4j
@DisplayName("E2E Flow 2: Campus Infrastructure Setup (Admin Journey)")
@SpringBootTest(classes = PromotionApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveDataAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.transaction.reactive.ReactiveTransactionAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration," +
                "org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
})
class CampusInfrastructureSetupFlow {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("circleguard_promotion")
            .withUsername("test")
            .withPassword("test");

    @LocalServerPort int port;

    @MockBean Neo4jSchemaConfig         neo4jSchemaConfig;
    @MockBean UserNodeRepository        userNodeRepository;
    @MockBean CircleNodeRepository      circleNodeRepository;
    @MockBean Neo4jClient               neo4jClient;
    @MockBean Driver                    neo4jDriver;
    @MockBean CircleService             circleService;
    @MockBean HealthStatusService       healthStatusService;
    @MockBean StatusLifecycleService    statusLifecycleService;
    @MockBean GraphService              graphService;
    @MockBean LocationResolutionService locationResolutionService;
    @MockBean AutoCircleService         autoCircleService;
    @MockBean MacSessionRegistry        macSessionRegistry;
    @MockBean SpatialService            spatialService;
    @MockBean SurveyListener            surveyListener;
    @MockBean
    @SuppressWarnings("rawtypes")
    KafkaTemplate kafkaTemplate;

    static final String ADMIN_JWT = TestDataBuilder.promotionAdminJwt();

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
    }

    // Each test is SELF-CONTAINED — creates its own data so no inter-test dependencies.

    @Test
    @Timeout(120)
    @DisplayName("STEP 1 — Admin creates Science building and it is persisted")
    void step1_AdminCreatesBuildingOnCampusMap() {
        String id = given()
                .header("Authorization", "Bearer " + ADMIN_JWT)
                .contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Science Building", TestDataBuilder.BUILDING_CODE_A))
                .when().post("/api/v1/buildings")
                .then().statusCode(200)
                .body("name", equalTo("Science Building")).body("id", notNullValue())
                .extract().path("id");

        assertThat(id).isNotNull();
        log.info("STEP 1 PASSED — Building id={}", id);
    }

    @Test
    @Timeout(120)
    @DisplayName("STEP 2 — Admin adds floors to building; list endpoint returns correct count")
    void step2_AdminAddsFloorsToBuilding() {
        String buildingId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT)
                .contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Engineering Bldg", "BLDG-ENG"))
                .post("/api/v1/buildings")
                .then().statusCode(200).extract().path("id");

        given().header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.floorJson(1, "Ground Floor"))
                .post("/api/v1/buildings/" + buildingId + "/floors")
                .then().statusCode(200).body("floorNumber", equalTo(1));

        given().header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.floorJson(2, "Second Floor"))
                .post("/api/v1/buildings/" + buildingId + "/floors")
                .then().statusCode(200);

        given().get("/api/v1/buildings/" + buildingId + "/floors")
                .then().statusCode(200).body("$", hasSize(2));

        log.info("STEP 2 PASSED — 2 floors added to building {}", buildingId);
    }

    @Test
    @Timeout(120)
    @DisplayName("STEP 3 — Admin registers WiFi APs on a floor; list returns 2 APs")
    void step3_AdminRegistersWifiAccessPoints() {
        // Unique MACs per test invocation (avoids DB unique constraint violations)
        String mac1 = TestDataBuilder.uniqueMac();
        String mac2 = TestDataBuilder.uniqueMac();

        String buildingId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Library", "BLDG-LIB-" + mac1.substring(0,2)))
                .post("/api/v1/buildings").then().statusCode(200).extract().path("id");

        String floorId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.floorJson(1, "Ground Floor"))
                .post("/api/v1/buildings/" + buildingId + "/floors")
                .then().statusCode(200).extract().path("id");

        given().header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.accessPointJson(mac1, "Entrance AP", 10.5, 20.0))
                .post("/api/v1/floors/" + floorId + "/access-points")
                .then().statusCode(200).body("macAddress", equalTo(mac1));

        given().header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.accessPointJson(mac2, "Lab AP", 45.0, 80.5))
                .post("/api/v1/floors/" + floorId + "/access-points")
                .then().statusCode(200);

        given().get("/api/v1/floors/" + floorId + "/access-points")
                .then().statusCode(200).body("$", hasSize(2));

        log.info("STEP 3 PASSED — 2 APs registered on floor {}", floorId);
    }

    @Test
    @Timeout(120)
    @DisplayName("STEP 4 — Campus map is readable (GET buildings returns list without authentication)")
    void step4_CampusTopologyReadable() {
        given().header("Authorization", "Bearer " + ADMIN_JWT)
                .contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Admin Block", "BLDG-ADMIN"))
                .post("/api/v1/buildings").then().statusCode(200);

        given().get("/api/v1/buildings")
                .then().statusCode(200).body("$", not(empty()));

        log.info("STEP 4 PASSED — Campus map readable (public GET endpoint works)");
    }

    @Test
    @Timeout(120)
    @DisplayName("STEP 5 — Admin updates WiFi AP coordinates after physical relocation")
    void step5_AdminUpdatesAccessPointCoordinates() {
        String gymMac = TestDataBuilder.uniqueMac();

        String buildingId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Sports Center", "BLDG-SPORT-" + gymMac.substring(0, 2)))
                .post("/api/v1/buildings").then().statusCode(200).extract().path("id");

        String floorId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.floorJson(1, "Main Hall"))
                .post("/api/v1/buildings/" + buildingId + "/floors")
                .then().statusCode(200).extract().path("id");

        String apId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.accessPointJson(gymMac, "Gym AP", 30.0, 50.0))
                .post("/api/v1/floors/" + floorId + "/access-points")
                .then().statusCode(200).extract().path("id");

        given().header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.accessPointJson(gymMac, "Gym AP (relocated)", 32.0, 55.0))
                .put("/api/v1/access-points/" + apId)
                .then().statusCode(200)
                .body("coordinateX", equalTo(32.0f))
                .body("coordinateY", equalTo(55.0f));

        log.info("STEP 5 PASSED — AP coordinates updated to (32.0, 55.0)");
    }

    @Test
    @Timeout(120)
    @DisplayName("STEP 6 — Multi-building campus: admin creates Library, verifies 2+ buildings, deletes it")
    void step6_MultiBuildingCampusComplete() {
        given().header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Science Wing", "BLDG-SCI"))
                .post("/api/v1/buildings").then().statusCode(200);

        String libraryId = given()
                .header("Authorization", "Bearer " + ADMIN_JWT).contentType(ContentType.JSON)
                .body(TestDataBuilder.buildingJson("Library", TestDataBuilder.BUILDING_CODE_B))
                .post("/api/v1/buildings")
                .then().statusCode(200).body("name", equalTo("Library"))
                .extract().path("id");

        given().get("/api/v1/buildings")
                .then().statusCode(200).body("$", hasSize(greaterThanOrEqualTo(2)));

        given().delete("/api/v1/buildings/" + libraryId).then().statusCode(200);

        log.info("STEP 6 PASSED — Multi-building campus verified; Library removed");
    }
}
