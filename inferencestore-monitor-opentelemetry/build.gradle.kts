plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

// OpenTelemetry's SDK is JVM/Android (Java), so this exporter is a JVM module.
kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    api(project(":inferencestore-core"))
    api(libs.opentelemetry.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(project(":inferencestore-testkit"))
}
