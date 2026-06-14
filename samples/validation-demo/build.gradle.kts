plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Depends ONLY on the canonical event model in core — never the routing engine.
    // The demo scripts the four flagship flows as RouteTrace data, so it runs
    // independently of the engine (OSS-6: "must not depend on M1 core implementation").
    implementation(project(":inferencestore-core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.mattramotar.inferencestore.samples.validation.MainKt")
}
