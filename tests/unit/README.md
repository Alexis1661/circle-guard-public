# Circle Guard — Unit Tests

Unit tests for the 6 core microservices using **JUnit 5 + Mockito + MockMvc**.

---

## Quick Start

```bash
# Run all unit tests
./tests/unit/run_unit_tests.sh

# Run tests for a single service
./tests/unit/run_unit_tests.sh circleguard-auth-service

# Run from Gradle directly (all services)
./gradlew test

# Run specific service
./gradlew :services:circleguard-auth-service:test

# Run with coverage report
./gradlew :services:circleguard-auth-service:test :services:circleguard-auth-service:jacocoTestReport
```

---

## Coverage Reports

After running tests, HTML reports are generated at:

| Report | Location |
|--------|----------|
| Test results | `services/{service}/build/reports/tests/test/index.html` |
| JaCoCo coverage | `services/{service}/build/reports/jacoco/test/html/index.html` |

Open them in a browser:
```bash
# macOS/Linux
open services/circleguard-auth-service/build/reports/tests/test/index.html

# Windows
start services/circleguard-auth-service/build/reports/tests/test/index.html
```

---

## Test Structure

### Test Files Created

| Service | Test File | Tests | What it validates |
|---------|-----------|-------|-------------------|
| **auth** | `LoginControllerTest.java` *(existing)* | 1 | Login success with mocked auth chain |
| **auth** | `LoginControllerAdditionalTest.java` *(new)* | 5 | Login failure (401), null password, visitor handoff, missing anonymousId (400), response format |
| **auth** | `QrTokenControllerTest.java` *(new)* | 6 | QR generation: happy path, no auth (4xx), UUID from principal, response fields, expiresIn=60, empty token |
| **identity** | `IdentityVaultControllerTest.java` *(existing)* | 4 | Lookup with/without permission, unauthenticated (401), not found (404) |
| **identity** | `IdentityMappingTest.java` *(new)* | 6 | Map identity happy path, service arg validation, idempotency, visitor registration, VISITOR\| prefix format, response structure |
| **promotion** | `HealthStatusControllerTest.java` *(existing)* | 4 | Confirm/resolve with HEALTH_CENTER, 403 without permission, 403 unauthenticated |
| **promotion** | `CircleControllerTest.java` *(new)* | 7 | Create circle, join by code, get user circles, toggle validity (403 without role), force fence (403/200 with role), add member |
| **promotion** | `EncounterControllerTest.java` *(new)* | 5 | Report encounter, default locationId=mobile_ble, toggle validity (403), force fence (403), AutoCircleService triggered |
| **notification** | `ExposureNotificationListenerTest.java` *(existing)* | 1 | Event processed without error |
| **notification** | `ExposureNotificationListenerAdditionalTest.java` *(new)* | 5 | SUSPECT dispatches + LMS sync, ACTIVE skipped, CONFIRMED dispatches, malformed JSON handled, missing status=UNKNOWN skipped |
| **notification** | `EmailServiceImplTest.java` *(new)* | 5 | Successful send, SMTP failure logs RETRY, recipient format (userId@example.com), subject, recover() logs FAILED |
| **form** | `HealthSurveyControllerTest.java` *(existing)* | 1 | Submit survey 200 |
| **form** | `QuestionnaireControllerTest.java` *(existing)* | 2 | Get active, create |
| **form** | `QuestionnaireControllerAdditionalTest.java` *(new)* | 5 | No active → 404, get all list, activate calls service, create with version, response contains UUID id |
| **form** | `CertificateValidationControllerTest.java` *(new)* | 5 | Get pending list, validate APPROVED, validate REJECTED, missing adminId → 400, empty pending list |
| **gateway** | `GateControllerTest.java` *(existing)* | 1 | GREEN validation |
| **gateway** | `GateControllerDenialTest.java` *(new)* | 5 | CONTAGIED denied, POTENTIAL denied, invalid token, null token handled, GREEN welcome message |
| **gateway** | `QrValidationServiceTest.java` *(existing)* | 2 | Valid token GREEN, CONTAGIED denied |
| **gateway** | `QrValidationServiceExtendedTest.java` *(new)* | 5 | POTENTIAL denied, expired token, null Redis=GREEN, malformed token, null token |

**Total: 74 tests** (16 existing + 58 new)

---

## Technologies Used

| Tool | Version | Purpose |
|------|---------|---------|
| JUnit 5 | Spring Boot BOM | Test runner, assertions |
| Mockito | Spring Boot BOM | Mock creation, behavior verification |
| Spring MockMvc | Spring Boot BOM | Simulate HTTP requests without a server |
| AssertJ | Spring Boot BOM | Fluent assertion library |
| `@WebMvcTest` | Spring Boot | Load only web layer (fast, isolated) |
| `@SpringBootTest` | Spring Boot | Full context (used for integration tests) |
| `@WithMockUser` | Spring Security Test | Inject fake authenticated user |
| `@MockBean` | Spring Boot Test | Replace Spring beans with Mockito mocks |

---

## Test Patterns Used

### AAA Pattern (Arrange-Act-Assert)

All test methods follow this structure:

```java
@Test
void scenarioName_WhenCondition_ExpectedOutcome() throws Exception {
    // Arrange — set up mocks and test data
    when(mockService.doSomething(any())).thenReturn(expectedResult);
    
    // Act — perform the action under test
    mockMvc.perform(post("/api/v1/endpoint")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"key\": \"value\"}"))
    
    // Assert — verify expected behavior
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.field").value("expected"));
    
    verify(mockService, times(1)).doSomething(any());
}
```

### Security Testing

For endpoints with `@PreAuthorize("hasRole('HEALTH_CENTER')")`:

```java
// ✅ Authorized — use roles= (adds ROLE_ prefix automatically)
@WithMockUser(roles = "HEALTH_CENTER")
void authorizedTest() { ... }

// ✅ Unauthorized — wrong role
@WithMockUser(roles = "STUDENT")
void unauthorizedTest() { ... }

// ✅ Unauthenticated — no @WithMockUser annotation
void unauthenticatedTest() { ... }
```

---

## What Each Service's Tests Validate

### Auth Service
- JWT token generation on successful login
- `401` response on invalid credentials (wrong password)
- QR token endpoint requires authentication (`4xx` without JWT)
- QR response always includes `qrToken` + `expiresIn: "60"`
- Visitor handoff generates token with VISITOR authority
- Missing `anonymousId` in visitor handoff returns `400`

### Identity Service
- `POST /map` returns a UUID `anonymousId` for a given real identity
- Service receives exact identity string passed in request
- `POST /visitor` constructs identity as `VISITOR|email|name|reason`
- `GET /lookup/{id}` requires `identity:lookup` authority → `403` without it
- Kafka audit event emitted on every lookup (success and failure)

### Promotion Service
- Circle creation stores name and locationId
- Joining a circle by invite code calls `circleService.joinCircle()`
- `PATCH /{id}/validity` requires `HEALTH_CENTER` role → `403` for STUDENT
- `POST /{id}/force-fence` requires `HEALTH_CENTER` role → `200` with correct role
- Encounter reporting always triggers `AutoCircleService.evaluateEncounter()`
- Missing `locationId` in encounter defaults to `"mobile_ble"`

### Notification Service
- `ExposureNotificationListener` dispatches for SUSPECT/CONFIRMED, skips ACTIVE/UNKNOWN
- `EmailServiceImpl` uses `userId@example.com` as recipient
- SMTP failure logs `RETRY` status, rethrows for Spring Retry
- After max retries exhausted, `recover()` logs `FAILED`
- Malformed Kafka event JSON is caught and logged without crashing the listener

### Form Service
- Survey submission persists via `HealthSurveyService.submitSurvey()`
- `GET /certificates/pending` returns all PENDING surveys
- `POST /certificates/{id}/validate` requires both `status` and `adminId` params
- Missing `adminId` returns `400 Bad Request`
- Questionnaire activation calls `service.activateQuestionnaire(id)`
- `GET /questionnaires/active` returns `404` when no active questionnaire exists

### Gateway Service
- Valid QR token with no health flag → `valid=true`, `status=GREEN`
- User with `CONTAGIED` Redis status → `valid=false`, `status=RED`
- User with `POTENTIAL` Redis status → `valid=false`, `status=RED`
- Null Redis status (no entry) → `valid=true`, `status=GREEN`
- Expired JWT token → `valid=false`, `"Invalid or Expired Token"`
- Null token → handled gracefully, `valid=false`

---

## Adding New Tests

1. Create `src/test/java/com/circleguard/{service}/{package}/NewTest.java`
2. Annotate with `@WebMvcTest(Controller.class)` + `@Import(SecurityConfig.class)` for web tests
3. Mock dependencies with `@MockBean`
4. Use `@WithMockUser` for authenticated requests
5. Follow the AAA pattern
6. Run `./gradlew :services:circleguard-{service}:test` to verify
