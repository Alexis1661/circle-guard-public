# Circle Guard — E2E Tests

End-to-End tests that validate **complete user flows** across multiple microservices.
Unlike integration tests (which validate service-to-service communication),
E2E tests simulate real user journeys from first action to final system state.

---

## Philosophy

| Level | Scope | Speed | What it proves |
|-------|-------|-------|----------------|
| Unit | One class | Milliseconds | Component logic is correct |
| Integration | Two services | Seconds | Services talk correctly |
| **E2E** | **Full user flow** | **Minutes** | **User can achieve their goal** |

---

## Quick Start

```bash
# Make executable (once)
chmod +x tests/e2e/run_e2e_tests.sh tests/e2e/cleanup_test_data.sh

# Run all E2E flows
./tests/e2e/run_e2e_tests.sh

# Run one specific flow
./tests/e2e/run_e2e_tests.sh --flow QRCampusGateFlow

# If a run fails mid-way, clean up containers
./tests/e2e/cleanup_test_data.sh

# Direct Gradle invocation
./gradlew :tests:e2e:test --info
```

---

## E2E Flows (7 flows, 38 test steps)

### Flow 1 — `UserLoginAndSystemAccessFlow`
**User Journey:** Student authenticates and accesses the campus system

| Step | Action | Expected |
|------|--------|----------|
| 1 | Auth service health check | Service responds |
| 2 | Login with invalid password | 401 Unauthorized |
| 3 | Login with non-existent user | 401 Unauthorized |
| 4 | Successful login → identity service called | WireMock captures HTTP call |
| 5 | JWT signed with shared secret | Any service can verify it |
| 6 | QR endpoint without JWT | 401 or 403 |
| 7 | Full flow: login → QR generation | JWT → QR token returned |
| 8 | Identity contract: no JWT → 401 | Contract between auth and identity |

**Services:** `auth-service` + `identity-service` (WireMock)  
**Infrastructure:** H2 in-memory + Embedded LDAP

---

### Flow 2 — `CampusInfrastructureSetupFlow`
**User Journey:** IT admin configures campus WiFi topology for contact tracing

| Step | Action | Expected |
|------|--------|----------|
| 1 | Admin creates Science building | Building stored in DB |
| 2 | Admin adds Ground floor and Second floor | Both floors linked to building |
| 3 | Admin registers WiFi access points | 2 APs on Ground floor |
| 4 | Read campus topology | All buildings/floors/APs retrievable |
| 5 | Update AP coordinates after relocation | Updated coordinates stored |
| 6 | Add Library building; verify multi-building campus | Campus map complete |

**Services:** `promotion-service` (JPA endpoints — Neo4j excluded)  
**Infrastructure:** PostgreSQL (TestContainers)

---

### Flow 3 — `StudentHealthSurveyJourneyFlow`
**User Journey:** Complete questionnaire lifecycle from creation to certificate approval

| Step | Action | Expected |
|------|--------|----------|
| 1 | Admin creates COVID screening questionnaire | Stored as inactive |
| 2 | Admin activates questionnaire | Now the active daily check |
| 3 | Student retrieves active questionnaire | Receives today's screening |
| 4 | Student reports fever + exposure date | survey.submitted Kafka event |
| 5 | Student attaches medical certificate | Status = PENDING |
| 6 | Admin approves certificate | certificate.validated Kafka event |

**Services:** `form-service`  
**Infrastructure:** PostgreSQL + EmbeddedKafka

---

### Flow 4 — `ExposureAlertPipelineFlow` _(Multi-Service)_
**User Journey:** From symptom report to alert dispatch (5-service data flow)

**Phase A — Form publishes events:**

| Step | Action | Expected |
|------|--------|----------|
| A-1 | Symptomatic survey submitted | survey.submitted Kafka event |
| A-2 | 3 students submit simultaneously | All 3 events reach Kafka |

**Phase B — Notification consumes events:**

| Step | Action | Expected |
|------|--------|----------|
| B-1 | SUSPECT status change published | ExposureNotificationListener fires |
| B-2 | CONFIRMED_CASE alert published | PriorityAlertListener fires |
| B-3 | LARGE_OUTBREAK (15 affected) | Emergency alert triggered |
| B-4 | circle.fenced event | CircleFencedListener cancels rooms |
| B-5 | Full cascade: SUSPECT→CONFIRMED→fenced | All 3 listeners process in sequence |

**Services:** `form-service` (Phase A) + `notification-service` (Phase B)  
**Infrastructure:** PostgreSQL + EmbeddedKafka (shared bus)

---

### Flow 5 — `QRCampusGateFlow` _(Multi-Service)_
**User Journey:** Student uses QR code to enter campus

**Phase A — Auth generates QR:**

| Step | Action | Expected |
|------|--------|----------|
| A-1 | Authenticated student generates QR | QR token returned |
| A-2 | Unauthenticated student tries to get QR | Rejected 401/403 |
| A-3 | QR token structure validation | Signed with shared QR_SECRET |

**Phase B — Gateway validates QR at physical gate:**

| Step | Action | Expected |
|------|--------|----------|
| B-1 | Valid QR presented at gate | valid=true, GREEN |
| B-2 | Expired QR presented | valid=false, RED |
| B-3 | Tampered QR (signature corrupted) | valid=false, RED |
| B-4 | Same valid QR scanned twice | valid=true (Redis cache) |
| B-5 | Missing token in request | valid=false, graceful handling |

**Services:** `auth-service` (Phase A) + `gateway-service` (Phase B)  
**Infrastructure:** H2 + Redis (TestContainers)

---

### Flow 6 — `HealthAlertDispatchFlow`
**User Journey:** Health authority manages a COVID-19 outbreak response

| Step | Action | Expected |
|------|--------|----------|
| 1 | SUSPECT status → exposure notification | ExposureNotificationListener |
| 2 | PROBABLE status → escalated notification | Increased monitoring triggered |
| 3 | CONFIRMED case + priority alert | Admins notified immediately |
| 4 | 20-person LARGE_OUTBREAK | Emergency protocol activated |
| 5 | Circle fenced → room cancellations | Facilities automatically updated |
| 6 | 5 concurrent events burst | No message loss under load |
| 7 | Full outbreak cascade (4 stages) | Complete notification chain verified |

**Services:** `notification-service`  
**Infrastructure:** EmbeddedKafka

---

### Flow 7 — `IdentityPrivacyProtectionFlow`
**User Journey:** Validates GDPR-compliant anonymous ID system

| Step | Action | Expected |
|------|--------|----------|
| 1 | Identity service enforces auth on all endpoints | 401/403 without JWT |
| 2 | Student JWT cannot access identity lookup | Privacy protected |
| 3 | Visitor registration creates anonymous ID | Real identity never exposed |
| 4 | JWT with wrong secret is rejected | Signature verification fails |
| 5 | Expired JWT is always rejected | No replay attacks |
| 6 | Auth service JWT (shared secret) accepted | Cross-service JWT contract |

**Services:** `identity-service`  
**Infrastructure:** H2 in-memory

---

## Architecture

```
 Tests JVM
 ┌─────────────────────────────────────────────────────────────────────┐
 │  Flow 1: Auth Service ─────────────────── WireMock :8083 (identity) │
 │  Flow 2: Promotion Service ──────────── PostgreSQL container         │
 │  Flow 3: Form Service ─────── PostgreSQL ──── EmbeddedKafka          │
 │  Flow 4: Form ──── EmbeddedKafka ──── Notification Service           │
 │  Flow 5: Auth (H2) ──── [QR token] ──── Gateway ──── Redis           │
 │  Flow 6: Notification ─────── EmbeddedKafka                          │
 │  Flow 7: Identity Service ──────────── H2 in-memory                  │
 └─────────────────────────────────────────────────────────────────────┘
```

---

## Infrastructure Used

| Resource | Used by | How |
|----------|---------|-----|
| H2 (in-memory) | Auth, Identity | `@TestPropertySource` (no container) |
| PostgreSQL 15 | Promotion, Form | TestContainers `PostgreSQLContainer` |
| Redis 7-alpine | Gateway | TestContainers `GenericContainer` |
| EmbeddedKafka | Form, Notification | Spring `@EmbeddedKafka` |
| WireMock | Auth → Identity | `WireMockServer` on port 8083 |
| Embedded LDAP | Auth | Spring Boot auto-configured |

---

## Execution Times (approximate)

| Flow | Startup | Tests | Total |
|------|---------|-------|-------|
| UserLoginAndSystemAccess | 15s | 10s | ~25s |
| CampusInfrastructureSetup | 25s | 15s | ~40s |
| StudentHealthSurveyJourney | 30s | 20s | ~50s |
| ExposureAlertPipeline | 35s | 30s | ~65s |
| QRCampusGate | 30s | 20s | ~50s |
| HealthAlertDispatch | 20s | 35s | ~55s |
| IdentityPrivacyProtection | 15s | 10s | ~25s |
| **Total** | | | **~6 minutes** |

---

## Prerequisites

- **Docker Desktop** running (required for TestContainers)
- **Java 21** installed
- **8 GB RAM** available (multiple service contexts + containers)
- **Gradle wrapper** present at repository root

---

## Troubleshooting

### "Docker daemon is not running"
Start Docker Desktop and wait for it to fully initialize.

### Tests time out
Increase the timeout in `build.gradle.kts` (`timeout.set(Duration.ofMinutes(20))`)
or check Docker has enough RAM allocated.

### "Cannot find bean of type KafkaTemplate"
Add `@MockBean AuditLogService` to the test class — this service needs
`KafkaTemplate<String, Object>` which may conflict with test producers.

### "Found more than one migration with version 1"  
Use `spring.flyway.enabled=false` with `ddl-auto=create-drop` for tests
that share the classpath with auth-service (which has its own V1 migration).

### Containers not stopped after test failure
```bash
./tests/e2e/cleanup_test_data.sh
```

---

## Differences from Integration Tests

| | Integration Tests | E2E Tests |
|-|-------------------|-----------|
| Location | `tests/integration/` | `tests/e2e/` |
| Focus | One interaction | Full user journey |
| Steps per test | 1-2 | 5-8 |
| Infrastructure | Same | Same + more |
| Test class size | Small | Larger |
| Purpose | API contracts | Business workflows |
