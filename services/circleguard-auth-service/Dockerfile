# ── Stage 1: Build ───────────────────────────────────────────────────────────
# Build from project root: docker build -f services/circleguard-auth-service/Dockerfile .
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

# Copy Gradle wrapper + root build scripts first (maximises layer cache hits)
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Copy only this service's source tree
COPY services/circleguard-auth-service/ services/circleguard-auth-service/

RUN tr -d '\r' < gradlew > gradlew.tmp && mv gradlew.tmp gradlew && chmod +x ./gradlew && \
    ./gradlew :services:circleguard-auth-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
USER appuser

COPY --from=builder /workspace/services/circleguard-auth-service/build/libs/*.jar app.jar

EXPOSE 8180

ENTRYPOINT ["java", "-jar", "app.jar"]
