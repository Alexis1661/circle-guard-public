#!/usr/bin/env bash
# run_integration_tests.sh — Run all Circle Guard integration tests
#
# Prerequisites: Docker running, Java 21, Gradle wrapper at repo root
# Usage: ./tests/integration/run_integration_tests.sh [--test TestClassName]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPORT_DIR="$SCRIPT_DIR/build/reports/tests/test"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$SCRIPT_DIR/build/integration-test-${TIMESTAMP}.log"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

header()  { echo -e "\n${CYAN}${BOLD}═══ $* ═══${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ $*${RESET}"; }
error()   { echo -e "${RED}✗ $*${RESET}"; }

# ── 1. Check Docker ───────────────────────────────────────────────────────────
header "Checking prerequisites"

if ! docker info &>/dev/null; then
    error "Docker is not running. Start Docker Desktop and retry."
    exit 1
fi
success "Docker is running"

if ! java -version &>/dev/null 2>&1; then
    error "Java not found. Install Java 21."
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [[ "$JAVA_VER" -lt 21 ]]; then
    warn "Java $JAVA_VER detected — Java 21+ recommended"
fi
success "Java $JAVA_VER found"

# ── 2. Parse args ─────────────────────────────────────────────────────────────
SPECIFIC_TEST=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --test) SPECIFIC_TEST="$2"; shift 2 ;;
        *) warn "Unknown argument: $1"; shift ;;
    esac
done

# ── 3. Build the project ──────────────────────────────────────────────────────
header "Building project (compiling service modules + integration tests)"
cd "$REPO_ROOT"

mkdir -p "$SCRIPT_DIR/build"

if [[ -n "$SPECIFIC_TEST" ]]; then
    GRADLE_ARGS="--tests \"*${SPECIFIC_TEST}*\""
    echo "Running specific test: $SPECIFIC_TEST"
else
    GRADLE_ARGS=""
fi

# ── 4. Run integration tests ──────────────────────────────────────────────────
header "Running integration tests (TestContainers will pull Docker images on first run)"
echo "Logs: $LOG_FILE"
echo "Report: $REPORT_DIR/index.html"
echo ""

START_TIME=$(date +%s)

set +e
./gradlew :tests:integration:test \
    --info \
    $GRADLE_ARGS \
    2>&1 | tee "$LOG_FILE"
EXIT_CODE=${PIPESTATUS[0]}
set -e

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

# ── 5. Results summary ────────────────────────────────────────────────────────
header "Test Results"

echo "Duration: ${ELAPSED}s"
echo ""

if [[ -f "$REPORT_DIR/index.html" ]]; then
    # Parse test counts from Gradle output
    PASSED=$(grep -c "PASSED" "$LOG_FILE" 2>/dev/null || echo "?")
    FAILED=$(grep -c "FAILED" "$LOG_FILE" 2>/dev/null || echo "0")
    SKIPPED=$(grep -c "SKIPPED" "$LOG_FILE" 2>/dev/null || echo "?")

    echo "  Tests passed : $PASSED"
    echo "  Tests failed : $FAILED"
    echo "  Tests skipped: $SKIPPED"
    echo ""
    echo "HTML Report  : $REPORT_DIR/index.html"
else
    warn "HTML report not found at $REPORT_DIR/index.html"
    echo "Check log file: $LOG_FILE"
fi

echo ""

if [[ $EXIT_CODE -eq 0 ]]; then
    success "ALL INTEGRATION TESTS PASSED"
    echo ""
    echo -e "${GREEN}${BOLD}Test suites executed:${RESET}"
    echo "  ✓ AuthIdentityIntegrationTest   — AUTH ↔ IDENTITY (HTTP + JWT)"
    echo "  ✓ AuthGatewayIntegrationTest    — AUTH ↔ GATEWAY (QR + Redis)"
    echo "  ✓ PromotionNotificationKafkaTest— PROMOTION → NOTIFICATION (Kafka)"
    echo "  ✓ FormNotificationKafkaTest     — FORM → NOTIFICATION (Kafka)"
    echo "  ✓ CircleEncounterFlowTest       — E2E multi-service pipeline"
    echo ""
    exit 0
else
    error "INTEGRATION TESTS FAILED (exit code: $EXIT_CODE)"
    echo ""
    echo "Failed tests:"
    grep "FAILED" "$LOG_FILE" 2>/dev/null | head -20 || true
    echo ""
    echo "Full log: $LOG_FILE"
    echo "HTML report: $REPORT_DIR/index.html"
    echo ""
    exit 1
fi
