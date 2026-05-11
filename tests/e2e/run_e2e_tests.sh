#!/usr/bin/env bash
# run_e2e_tests.sh — Execute Circle Guard E2E test suite
#
# Validates COMPLETE USER FLOWS across multiple microservices.
# Each E2E flow boots real service contexts + TestContainers infrastructure.
#
# Usage:
#   ./tests/e2e/run_e2e_tests.sh
#   ./tests/e2e/run_e2e_tests.sh --flow UserLoginAndSystemAccessFlow
#   ./tests/e2e/run_e2e_tests.sh --flow QRCampusGateFlow

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_DIR="$SCRIPT_DIR/build/reports/tests/test"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$SCRIPT_DIR/build/e2e-test-${TIMESTAMP}.log"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

header()  { echo -e "\n${CYAN}${BOLD}═══ $* ═══${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ $*${RESET}"; }
error()   { echo -e "${RED}✗ $*${RESET}"; }

# ── 1. Prerequisites check ────────────────────────────────────────────────────
header "Checking Prerequisites"

if ! docker info &>/dev/null; then
    error "Docker is not running. E2E tests use TestContainers (PostgreSQL, Redis, Neo4j)."
    error "Start Docker Desktop and retry."
    exit 1
fi
success "Docker is running"

if ! java -version &>/dev/null 2>&1; then
    error "Java not found. Install Java 21."
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [[ "$JAVA_VER" -lt 21 ]]; then
    warn "Java ${JAVA_VER} detected — Java 21 recommended"
fi
success "Java ${JAVA_VER} detected"

# ── 2. Parse arguments ────────────────────────────────────────────────────────
SPECIFIC_FLOW=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --flow) SPECIFIC_FLOW="$2"; shift 2 ;;
        --help)
            echo "Usage: $0 [--flow FlowClassName]"
            echo ""
            echo "Available E2E flows:"
            echo "  UserLoginAndSystemAccessFlow        (auth service)"
            echo "  CampusInfrastructureSetupFlow       (promotion service JPA)"
            echo "  StudentHealthSurveyJourneyFlow      (form service + Kafka)"
            echo "  ExposureAlertPipelineFlow           (form + notification, multi-service)"
            echo "  QRCampusGateFlow                    (auth + gateway, multi-service)"
            echo "  HealthAlertDispatchFlow             (notification service)"
            echo "  IdentityPrivacyProtectionFlow       (identity service)"
            exit 0 ;;
        *) warn "Unknown argument: $1"; shift ;;
    esac
done

# ── 3. Build and run ──────────────────────────────────────────────────────────
header "Running E2E Tests (TestContainers will pull Docker images on first run)"
echo "Expected run time: 4-6 minutes (container startup + service boot)"
echo "Log file: $LOG_FILE"
echo "Report:   $REPORT_DIR/index.html"

mkdir -p "$SCRIPT_DIR/build"

cd "$REPO_ROOT"

if [[ -n "$SPECIFIC_FLOW" ]]; then
    GRADLE_TEST_FILTER="--tests \"*${SPECIFIC_FLOW}*\""
    echo "Running specific flow: $SPECIFIC_FLOW"
else
    GRADLE_TEST_FILTER=""
    echo "Running ALL E2E flows"
fi

START_TIME=$(date +%s)

set +e
eval ./gradlew :tests:e2e:test --info $GRADLE_TEST_FILTER 2>&1 | tee "$LOG_FILE"
EXIT_CODE=${PIPESTATUS[0]}
set -e

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

# ── 4. Results ────────────────────────────────────────────────────────────────
header "E2E Test Results"
echo "Duration: ${ELAPSED}s"
echo ""

PASSED=$(grep -c " PASSED" "$LOG_FILE" 2>/dev/null || echo "0")
FAILED=$(grep -c " FAILED" "$LOG_FILE" 2>/dev/null || echo "0")

echo "  Tests PASSED: $PASSED"
echo "  Tests FAILED: $FAILED"
echo ""

if [[ -f "$REPORT_DIR/index.html" ]]; then
    echo "HTML Report: $REPORT_DIR/index.html"
fi
echo ""

if [[ $EXIT_CODE -eq 0 ]]; then
    success "ALL E2E TESTS PASSED"
    echo ""
    echo -e "${GREEN}${BOLD}E2E Flows executed:${RESET}"
    echo "  ✓ UserLoginAndSystemAccessFlow    — Complete auth journey (login→JWT→QR)"
    echo "  ✓ CampusInfrastructureSetupFlow   — Admin creates campus topology (CRUD)"
    echo "  ✓ StudentHealthSurveyJourneyFlow  — Full survey lifecycle (create→submit→approve)"
    echo "  ✓ ExposureAlertPipelineFlow       — Form→Kafka→Notification multi-service"
    echo "  ✓ QRCampusGateFlow               — Auth→Gateway QR access control"
    echo "  ✓ HealthAlertDispatchFlow         — Complete notification pipeline"
    echo "  ✓ IdentityPrivacyProtectionFlow   — JWT security + anonymous ID system"
    echo ""
    exit 0
else
    error "E2E TESTS FAILED (exit code: $EXIT_CODE)"
    echo ""
    echo "Failed tests:"
    grep " FAILED" "$LOG_FILE" 2>/dev/null | head -20 || true
    echo ""
    echo "Full log: $LOG_FILE"
    echo ""
    echo "Troubleshooting:"
    echo "  1. Ensure Docker Desktop is running and has sufficient resources (4GB RAM)"
    echo "  2. Check available disk space (containers need ~500MB)"
    echo "  3. Run individual flow: $0 --flow FlowName"
    echo "  4. Check container logs with: docker ps && docker logs <container_id>"
    echo ""
    exit 1
fi
