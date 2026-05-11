#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p reports

check_service() {
    local name=$1 url=$2
    if curl -sf "$url" -o /dev/null --connect-timeout 3 2>/dev/null; then
        echo "  [OK] $name ($url)"
    else
        echo "  [WARN] $name no responde en $url — asegurate de que este corriendo"
    fi
}

echo "============================================"
echo "  Circle Guard — Load Test Suite"
echo "============================================"
echo ""
echo "Verificando servicios..."
check_service "auth-service"      "http://localhost:8180/actuator/health"
check_service "gateway-service"   "http://localhost:8087/actuator/health"
check_service "promotion-service" "http://localhost:8088/actuator/health"
check_service "form-service"      "http://localhost:8086/actuator/health"
echo ""

run_scenario() {
    local name=$1 users=$2 rate=$3 duration=$4 csv=$5
    echo "--------------------------------------------"
    echo "Escenario: $name | Usuarios: $users | Duracion: ${duration}s"
    echo "--------------------------------------------"
    locust -f locustfile.py \
        --host http://localhost:8180 \
        -u "$users" -r "$rate" -t "${duration}s" \
        --headless \
        --csv="reports/$csv" \
        --csv-full-history \
        2>&1 | grep -E "INFO|WARNING|ERROR|Aggregated" || true
    echo "  -> Reporte: reports/${csv}_stats.csv"
    echo ""
}

run_scenario "1. Baseline" 10  1  30  "baseline"
run_scenario "2. Normal"   50  2  60  "normal"
run_scenario "3. Peak"     200 5  120 "peak"
run_scenario "4. Stress"   500 10 180 "stress"

echo "============================================"
echo "  COMPLETADO — Reportes en reports/"
echo "============================================"
ls -lh reports/*.csv 2>/dev/null || echo "(no se generaron CSVs — verifica que los servicios esten corriendo)"
