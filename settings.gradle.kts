pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
// }

rootProject.name = "circleguard"

// Shared Libraries (Commented out as folders are missing on disk)
// include("libs:circleguard-common")
// include("libs:circleguard-security")
// include("libs:circleguard-events")

// Integration Tests
include("tests:integration")

// E2E Tests
include("tests:e2e")

// Microservices
include("services:circleguard-auth-service")
include("services:circleguard-identity-service")
include("services:circleguard-promotion-service")
include("services:circleguard-notification-service")
include("services:circleguard-form-service")
include("services:circleguard-file-service")
include("services:circleguard-gateway-service")
include("services:circleguard-dashboard-service")
