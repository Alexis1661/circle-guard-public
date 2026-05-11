# Circle Guard — Performance Tests

Pruebas de carga con Locust para los 4 microservicios principales.

## Requisitos

```bash
pip install -r requirements.txt
```

## Servicios requeridos (corriendo)

| Servicio         | Puerto |
|------------------|--------|
| auth-service     | 8180   |
| gateway-service  | 8087   |
| promotion-service| 8088   |
| form-service     | 8086   |

Iniciar con Docker Compose:
```bash
docker-compose -f docker-compose.test.yml up -d
```

## Ejecutar todos los escenarios

```bash
chmod +x run_load_test.sh
./run_load_test.sh
```

Duración total: ~6.5 minutos (30 + 60 + 120 + 180 segundos).

## Escenarios

| # | Nombre   | Usuarios | Spawn rate | Duración |
|---|----------|----------|------------|----------|
| 1 | Baseline | 10       | 1/s        | 30s      |
| 2 | Normal   | 50       | 2/s        | 60s      |
| 3 | Peak     | 200      | 5/s        | 120s     |
| 4 | Stress   | 500      | 10/s       | 180s     |

## Reportes generados

Cada escenario genera 3 archivos en `reports/`:

- `<scenario>_stats.csv` — métricas por endpoint (p50, p95, p99, error count)
- `<scenario>_stats_history.csv` — series de tiempo
- `<scenario>_failures.csv` — detalle de errores

## Endpoints probados

| Endpoint                  | Servicio   | Peso |
|---------------------------|------------|------|
| POST /auth/login          | auth       | on_start |
| GET  /auth/qr/generate    | auth       | 2x   |
| POST /gate/validate       | gateway    | 3x   |
| GET  /buildings           | promotion  | 2x   |
| POST /buildings           | promotion  | 1x   |
| POST /surveys             | form       | 3x   |
| GET  /questionnaires      | form       | 1x   |

## Ejecución manual (un escenario)

```bash
locust -f locustfile.py --host http://localhost:8180 -u 50 -r 2 -t 60s --headless --csv=reports/normal
```

Con UI interactiva:
```bash
locust -f locustfile.py --host http://localhost:8180
# Abrir http://localhost:8089
```
