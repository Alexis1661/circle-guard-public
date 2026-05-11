#!/usr/bin/env bash
# cleanup_test_data.sh — Clean up any leftover E2E test infrastructure
#
# Use when a test fails mid-run and leaves TestContainers running.
# TestContainers normally auto-cleans via Ryuk, but this script forces cleanup.

set -euo pipefail

CYAN='\033[0;36m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RESET='\033[0m'
header()  { echo -e "\n${CYAN}$*${RESET}"; }
success() { echo -e "${GREEN}✓ $*${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ $*${RESET}"; }

header "Circle Guard E2E Test Cleanup"

if ! docker info &>/dev/null; then
    warn "Docker not running — nothing to clean"
    exit 0
fi

# Stop all TestContainers-created containers
header "Stopping TestContainers resources..."

TESTCONTAINER_IDS=$(docker ps --filter "label=org.testcontainers=true" -q 2>/dev/null || echo "")
if [[ -n "$TESTCONTAINER_IDS" ]]; then
    echo "Stopping containers: $TESTCONTAINER_IDS"
    docker stop $TESTCONTAINER_IDS 2>/dev/null || true
    docker rm   $TESTCONTAINER_IDS 2>/dev/null || true
    success "TestContainers cleaned up"
else
    success "No TestContainers found (already clean)"
fi

# Prune dangling volumes
header "Pruning unused Docker volumes..."
docker volume prune -f 2>/dev/null || true
success "Volumes pruned"

# Clean Gradle test caches
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$SCRIPT_DIR/build" ]]; then
    header "Cleaning E2E build directory..."
    rm -rf "$SCRIPT_DIR/build/test-results" "$SCRIPT_DIR/build/reports"
    success "Build artifacts cleaned"
fi

echo ""
success "Cleanup complete. Ready for a fresh E2E run."
echo "Run tests with: ./tests/e2e/run_e2e_tests.sh"
