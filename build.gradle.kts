plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) // root aggregates the multi-module API reference
}

allprojects {
    // Coordinates + version come from gradle.properties (GROUP / VERSION_NAME) so the
    // release process bumps a single source of truth.
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// The published library modules whose public API ships in the generated reference
// (`./gradlew dokkaGenerate` → build/dokka/html). Samples are intentionally excluded.
val apiReferenceModules = listOf(
    "inferencestore-core",
    "inferencestore-testkit",
    "inferencestore-provider-openai-compatible",
    "inferencestore-provider-litertlm-android",
    "inferencestore-provider-firebase-android",
    "inferencestore-provider-apple-foundation",
    "inferencestore-store-sqldelight",
    "inferencestore-monitor-opentelemetry",
)

subprojects {
    if (name in apiReferenceModules) {
        apply(plugin = "org.jetbrains.dokka")
    }
}

dependencies {
    apiReferenceModules.forEach { add("dokka", project(":$it")) }
}

dokka {
    moduleName.set("InferenceStore")
}
