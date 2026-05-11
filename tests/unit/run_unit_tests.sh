#!/usr/bin/env bash
# =============================================================================
# run_unit_tests.sh — Circle Guard Unit Test Runner
# =============================================================================
# Usage:
#   chmod +x tests/unit/run_unit_tests.sh
#   ./tests/unit/run_unit_tests.sh [service]
#
# Optional argument: specific service module name, e.g. "circleguard-auth-service"
# Without argument: runs tests for all 6 services.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COVERAGE_THRESHOLD=70

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SERVICES=(
  "circleguard-auth-service"
  "circleguard-identity-service"
  "circleguard-promotion-service"
  "circleguard-notification-service"
  "circleguard-form-service"
  "circleguard-gateway-service"
)

# ── Helper functions ──────────────────────────────────────────────────────────

print_header() {
  echo -e "\n${BLUE}════════════════════════════════════════════════════════════${NC}"
  echo -e "${BLUE}  $1${NC}"
  echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}\n"
}

print_success() { echo -e "${GREEN}✔ $1${NC}"; }
print_error()   { echo -e "${RED}✘ $1${NC}"; }
print_warn()    { echo -e "${YELLOW}⚠ $1${NC}"; }
print_info()    { echo -e "${BLUE}ℹ $1${NC}"; }

# ── Parse argument ────────────────────────────────────────────────────────────

TARGET_SERVICES=("${SERVICES[@]}")
if [[ $# -ge 1 ]]; then
  TARGET_SERVICES=("$1")
  print_info "Running tests for single service: $1"
fi

# ── Change to project root ────────────────────────────────────────────────────

cd "$PROJECT_ROOT"
print_header "Circle Guard — Unit Test Suite"
print_info "Project root: $PROJECT_ROOT"
print_info "Coverage threshold: ${COVERAGE_THRESHOLD}%"

# ── Check for Gradle wrapper ──────────────────────────────────────────────────

if [[ ! -f "./gradlew" ]]; then
  print_error "Gradle wrapper not found at $PROJECT_ROOT/gradlew"
  exit 1
fi

chmod +x ./gradlew

# ── Run tests for each service ────────────────────────────────────────────────

FAILED_SERVICES=()
PASSED_SERVICES=()

for SERVICE in "${TARGET_SERVICES[@]}"; do
  MODULE=":services:$SERVICE"
  print_header "Testing $SERVICE"

  # Run tests with JaCoCo report generation
  if ./gradlew "$MODULE:test" "$MODULE:jacocoTestReport" \
      --info \
      --continue \
      -x javadoc \
      2>&1 | tee "/tmp/test-output-$SERVICE.log"; then
    print_success "$SERVICE — all tests PASSED"
    PASSED_SERVICES+=("$SERVICE")
  else
    print_error "$SERVICE — tests FAILED"
    FAILED_SERVICES+=("$SERVICE")
  fi

  # Locate and display HTML report path
  REPORT_DIR="$PROJECT_ROOT/services/$SERVICE/build/reports"
  HTML_REPORT="$REPORT_DIR/tests/test/index.html"
  COVERAGE_REPORT="$REPORT_DIR/jacoco/test/html/index.html"

  if [[ -f "$HTML_REPORT" ]]; then
    print_info "Test report:     file://$HTML_REPORT"
  fi
  if [[ -f "$COVERAGE_REPORT" ]]; then
    print_info "Coverage report: file://$COVERAGE_REPORT"
  fi

  # ── Coverage check (parse JaCoCo XML) ──────────────────────────────────────
  JACOCO_XML="$PROJECT_ROOT/services/$SERVICE/build/reports/jacoco/test/jacocoTestReport.xml"
  if [[ -f "$JACOCO_XML" ]]; then
    # Extract overall instruction coverage percentage from XML
    MISSED=$(grep -oP '(?<=<counter type="INSTRUCTION" missed=")[0-9]+' "$JACOCO_XML" | head -1 || echo "0")
    COVERED=$(grep -oP '(?<=covered=")[0-9]+' "$JACOCO_XML" | head -1 || echo "0")
    TOTAL=$((MISSED + COVERED))
    if [[ $TOTAL -gt 0 ]]; then
      COVERAGE=$(( COVERED * 100 / TOTAL ))
      if [[ $COVERAGE -ge $COVERAGE_THRESHOLD ]]; then
        print_success "$SERVICE coverage: ${COVERAGE}% (≥ ${COVERAGE_THRESHOLD}%)"
      else
        print_warn "$SERVICE coverage: ${COVERAGE}% (below ${COVERAGE_THRESHOLD}% threshold)"
      fi
    fi
  fi

  echo ""
done

# ── Summary ───────────────────────────────────────────────────────────────────

print_header "Test Summary"

if [[ ${#PASSED_SERVICES[@]} -gt 0 ]]; then
  echo -e "${GREEN}PASSED (${#PASSED_SERVICES[@]}):${NC}"
  for s in "${PASSED_SERVICES[@]}"; do
    print_success "  $s"
  done
fi

if [[ ${#FAILED_SERVICES[@]} -gt 0 ]]; then
  echo -e "\n${RED}FAILED (${#FAILED_SERVICES[@]}):${NC}"
  for s in "${FAILED_SERVICES[@]}"; do
    print_error "  $s"
  done
  echo ""
  print_error "Some tests failed. Check the reports above for details."
  exit 1
fi

echo ""
print_success "All unit tests PASSED across ${#PASSED_SERVICES[@]} service(s)."
exit 0
