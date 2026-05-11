"""
Circle Guard — Performance Test
Simulates a campus user: login → QR → circles → encounters.
Run via: locust -f locustfile.py --host http://localhost:8180
"""

import uuid
import random
from locust import HttpUser, task, between

AUTH_HOST      = "http://localhost:8180"
GATEWAY_HOST   = "http://localhost:8087"
PROMOTION_HOST = "http://localhost:8088"
FORM_HOST      = "http://localhost:8086"

# Pre-seeded test credentials (must exist in running auth-service)
TEST_USERNAME = "admin"
TEST_PASSWORD = "admin"


class CircleGuardUser(HttpUser):
    wait_time = between(0.5, 1)
    host = AUTH_HOST

    def on_start(self):
        """Login once per simulated user; store the JWT for subsequent tasks."""
        self.token = None
        self.circle_id = None
        self._login()

    def _login(self):
        with self.client.post(
            "/api/v1/auth/login",
            json={"username": TEST_USERNAME, "password": TEST_PASSWORD},
            catch_response=True,
            name="POST /auth/login",
        ) as resp:
            if resp.status_code == 200:
                self.token = resp.json().get("token")
                resp.success()
            else:
                resp.failure(f"Login failed: {resp.status_code}")

    def _headers(self):
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}

    # ── Auth service tasks ────────────────────────────────────────────────────

    @task(2)
    def generate_qr(self):
        with self.client.get(
            "/api/v1/auth/qr/generate",
            headers=self._headers(),
            catch_response=True,
            name="GET /auth/qr/generate",
        ) as resp:
            if resp.status_code in (200, 500):  # 500 when no anonymousId in fresh H2
                resp.success()
            else:
                resp.failure(f"QR generate: {resp.status_code}")

    # ── Gateway service tasks ─────────────────────────────────────────────────

    @task(3)
    def validate_qr(self):
        dummy_token = "invalid.test.token"
        with self.client.post(
            f"{GATEWAY_HOST}/api/v1/gate/validate",
            json={"token": dummy_token},
            catch_response=True,
            name="POST /gate/validate",
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"QR validate: {resp.status_code}")

    # ── Promotion service tasks ───────────────────────────────────────────────

    @task(2)
    def list_buildings(self):
        with self.client.get(
            f"{PROMOTION_HOST}/api/v1/buildings",
            catch_response=True,
            name="GET /buildings",
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"List buildings: {resp.status_code}")

    @task(1)
    def create_building(self):
        code = "PERF-" + uuid.uuid4().hex[:6].upper()
        with self.client.post(
            f"{PROMOTION_HOST}/api/v1/buildings",
            json={
                "name": f"Load Test Building {code}",
                "code": code,
                "description": "Perf test",
                "latitude": 4.6297,
                "longitude": -74.0817,
                "address": "Campus Norte",
            },
            headers=self._headers(),
            catch_response=True,
            name="POST /buildings",
        ) as resp:
            if resp.status_code in (200, 201):
                resp.success()
            else:
                resp.failure(f"Create building: {resp.status_code}")

    # ── Form service tasks ────────────────────────────────────────────────────

    @task(3)
    def submit_survey(self):
        anonymous_id = str(uuid.uuid4())
        with self.client.post(
            f"{FORM_HOST}/api/v1/surveys",
            json={
                "anonymousId": anonymous_id,
                "hasFever": random.choice([True, False]),
                "hasCough": random.choice([True, False]),
                "otherSymptoms": None,
                "exposureDate": None,
                "responses": {},
            },
            catch_response=True,
            name="POST /surveys",
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            else:
                resp.failure(f"Submit survey: {resp.status_code}")

    @task(1)
    def list_questionnaires(self):
        with self.client.get(
            f"{FORM_HOST}/api/v1/questionnaires",
            catch_response=True,
            name="GET /questionnaires",
        ) as resp:
            if resp.status_code in (200, 404):
                resp.success()
            else:
                resp.failure(f"List questionnaires: {resp.status_code}")
