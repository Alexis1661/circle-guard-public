#!/usr/bin/env bash
# run_all_tests.sh — Circle Guard master test runner
# Ejecuta unit → integration → E2E → performance y genera REPORTE_FINAL.md
# Uso: ./tests/run_all_tests.sh [--skip-performance]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TESTS_DIR="$REPO_ROOT/tests"
RESULTS_DIR="$TESTS_DIR/results"
SKIP_PERF=false
[[ "${1:-}" == "--skip-performance" ]] && SKIP_PERF=true

mkdir -p "$RESULTS_DIR"

# ── Colores y helpers ─────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

print_header() { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════════════${NC}"; echo -e "${BOLD}${BLUE}  $1${NC}"; echo -e "${BOLD}${BLUE}══════════════════════════════════════════════${NC}"; }
print_ok()     { echo -e "  ${GREEN}✅ $1${NC}"; }
print_fail()   { echo -e "  ${RED}❌ $1${NC}"; }
print_warn()   { echo -e "  ${YELLOW}⚠️  $1${NC}"; }
print_info()   { echo -e "  ${CYAN}ℹ  $1${NC}"; }

spinner() {
    local pid=$1 msg=$2
    local frames=('⠋' '⠙' '⠹' '⠸' '⠼' '⠴' '⠦' '⠧' '⠇' '⠏')
    local i=0
    while kill -0 "$pid" 2>/dev/null; do
        printf "\r  ${CYAN}%s${NC}  %s " "${frames[$((i % 10))]}" "$msg"
        sleep 0.1; ((i++))
    done
    printf "\r%60s\r" ""   # clear line
}

run_suite() {
    local name="$1" script="$2" outfile="$3"
    local start end elapsed status=0
    start=$(date +%s)
    print_info "Iniciando: $name"

    (cd "$REPO_ROOT" && bash "$script" > "$outfile" 2>&1) &
    local pid=$!
    spinner "$pid" "$name"
    wait "$pid" && status=0 || status=$?

    end=$(date +%s)
    elapsed=$(( end - start ))

    echo "EXIT_CODE=$status"   >> "$outfile"
    echo "ELAPSED_SECS=$elapsed" >> "$outfile"
    echo "SUITE_NAME=$name"    >> "$outfile"
    echo "TIMESTAMP_END=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$outfile"

    if [ $status -eq 0 ]; then
        print_ok "$name — ${elapsed}s"
    else
        print_fail "$name — ${elapsed}s (exit $status)"
    fi
    return $status
}

# ── Global state ──────────────────────────────────────────────────────────────
GRAND_START=$(date +%s)
declare -A SUITE_STATUS
declare -A SUITE_ELAPSED

# ── HEADER ────────────────────────────────────────────────────────────────────
clear
echo -e "${BOLD}"
echo "  ██████╗██╗██████╗  ██████╗██╗     ███████╗     ██████╗ ██╗   ██╗ █████╗ ██████╗ ██████╗"
echo "  ██╔════╝██║██╔══██╗██╔════╝██║     ██╔════╝    ██╔════╝ ██║   ██║██╔══██╗██╔══██╗██╔══██╗"
echo "  ██║     ██║██████╔╝██║     ██║     █████╗      ██║  ███╗██║   ██║███████║██████╔╝██║  ██║"
echo "  ██║     ██║██╔══██╗██║     ██║     ██╔══╝      ██║   ██║██║   ██║██╔══██║██╔══██╗██║  ██║"
echo "  ╚██████╗██║██║  ██║╚██████╗███████╗███████╗    ╚██████╔╝╚██████╔╝██║  ██║██║  ██║██████╔╝"
echo "   ╚═════╝╚═╝╚═╝  ╚═╝ ╚═════╝╚══════╝╚══════╝     ╚═════╝  ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝"
echo -e "${NC}"
echo -e "  ${BOLD}Taller 2: Pruebas y Lanzamiento — Suite Maestra${NC}"
echo -e "  $(date '+%Y-%m-%d %H:%M:%S')\n"

# ── 1. UNIT TESTS ─────────────────────────────────────────────────────────────
print_header "1/4  UNIT TESTS (99 esperados)"
if run_suite "Unit Tests" "tests/unit/run_unit_tests.sh" "$RESULTS_DIR/unit_results.txt"; then
    SUITE_STATUS[unit]="PASSED"
else
    SUITE_STATUS[unit]="FAILED"
fi
SUITE_ELAPSED[unit]=$(grep "ELAPSED_SECS=" "$RESULTS_DIR/unit_results.txt" 2>/dev/null | cut -d= -f2 || echo 0)

# ── 2. INTEGRATION TESTS ──────────────────────────────────────────────────────
print_header "2/4  INTEGRATION TESTS (29 esperados)"
if run_suite "Integration Tests" "tests/integration/run_integration_tests.sh" "$RESULTS_DIR/integration_results.txt"; then
    SUITE_STATUS[integration]="PASSED"
else
    SUITE_STATUS[integration]="FAILED"
    print_warn "Integration tests fallaron — continuando con E2E"
fi
SUITE_ELAPSED[integration]=$(grep "ELAPSED_SECS=" "$RESULTS_DIR/integration_results.txt" 2>/dev/null | cut -d= -f2 || echo 0)

# ── 3. E2E TESTS ──────────────────────────────────────────────────────────────
print_header "3/4  E2E TESTS (48 esperados)"
if run_suite "E2E Tests" "tests/e2e/run_e2e_tests.sh" "$RESULTS_DIR/e2e_results.txt"; then
    SUITE_STATUS[e2e]="PASSED"
else
    SUITE_STATUS[e2e]="FAILED"
    print_warn "E2E tests fallaron — continuando con performance"
fi
SUITE_ELAPSED[e2e]=$(grep "ELAPSED_SECS=" "$RESULTS_DIR/e2e_results.txt" 2>/dev/null | cut -d= -f2 || echo 0)

# ── 4. PERFORMANCE TESTS ──────────────────────────────────────────────────────
print_header "4/4  PERFORMANCE TESTS (4 escenarios Locust)"
if $SKIP_PERF; then
    print_warn "Performance omitido por flag --skip-performance"
    SUITE_STATUS[performance]="SKIPPED"
    echo "SKIPPED=true" > "$RESULTS_DIR/performance_results.txt"
elif run_suite "Performance Tests" "tests/performance/run_load_test.sh" "$RESULTS_DIR/performance_results.txt"; then
    SUITE_STATUS[performance]="PASSED"
else
    SUITE_STATUS[performance]="FAILED"
    print_warn "Performance tests fallaron (no crítico)"
fi
SUITE_ELAPSED[performance]=$(grep "ELAPSED_SECS=" "$RESULTS_DIR/performance_results.txt" 2>/dev/null | cut -d= -f2 || echo 0)

# ── RESUMEN FINAL DE CONSOLA ──────────────────────────────────────────────────
GRAND_END=$(date +%s)
GRAND_ELAPSED=$(( GRAND_END - GRAND_START ))
GRAND_MIN=$(( GRAND_ELAPSED / 60 ))
GRAND_SEC=$(( GRAND_ELAPSED % 60 ))

echo ""
echo -e "${BOLD}══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  RESUMEN FINAL${NC}"
echo -e "${BOLD}══════════════════════════════════════════════${NC}"

TOTAL_PASSED=0; TOTAL_FAILED=0
for suite in unit integration e2e performance; do
    st="${SUITE_STATUS[$suite]:-UNKNOWN}"
    el="${SUITE_ELAPSED[$suite]:-0}"
    case "$st" in
        PASSED)  echo -e "  ${GREEN}✅ $suite${NC} (${el}s)"; ((TOTAL_PASSED++)) ;;
        FAILED)  echo -e "  ${RED}❌ $suite${NC} (${el}s)"; ((TOTAL_FAILED++)) ;;
        SKIPPED) echo -e "  ${YELLOW}⏭  $suite${NC} (skipped)" ;;
    esac
done

echo ""
echo -e "  ${BOLD}Suites PASSED : $TOTAL_PASSED${NC}"
echo -e "  ${BOLD}Suites FAILED : $TOTAL_FAILED${NC}"
echo -e "  ${BOLD}Tiempo total  : ${GRAND_MIN}m${GRAND_SEC}s${NC}"
echo ""

# ── GENERAR REPORTE FINAL ─────────────────────────────────────────────────────
echo -e "  ${CYAN}Generando REPORTE_FINAL.md...${NC}"
bash "$TESTS_DIR/generate_final_report.sh"
echo ""
print_ok "Reporte generado en: tests/REPORTE_FINAL.md"

# ── OPEN IN BROWSER (opcional) ────────────────────────────────────────────────
REPORT_PATH="$TESTS_DIR/REPORTE_FINAL.md"
if command -v xdg-open &>/dev/null; then
    xdg-open "$REPORT_PATH" 2>/dev/null &
elif command -v open &>/dev/null; then
    open "$REPORT_PATH" 2>/dev/null &
elif command -v start &>/dev/null; then
    start "" "$REPORT_PATH" 2>/dev/null || true
fi

# Exit non-zero si alguna suite crítica falló
[ "$TOTAL_FAILED" -gt 0 ] && exit 1 || exit 0
