# ── Stage 1: Build ───────────────────────────────────────────────────────────
# Build from project root: docker build -f services/circleguard-notification-service/Dockerfile .
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

COPY services/circleguard-notification-service/ services/circleguard-notification-service/

RUN tr -d '\r' < gradlew > gradlew.tmp && mv gradlew.tmp gradlew && chmod +x ./gradlew && \
    ./gradlew :services:circleguard-notification-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser

COPY --from=builder /workspace/services/circleguard-notification-service/build/libs/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
