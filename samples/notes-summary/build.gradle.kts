plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":inferencestore-core"))
    implementation(project(":inferencestore-testkit"))
    implementation(project(":inferencestore-provider-openai-compatible"))
    implementation(project(":inferencestore-provider-litertlm-android"))
    implementation(libs.kotlinx.coroutines.core)
    // The OpenAI-compatible adapter is engine-agnostic; the sample wires a mock HTTP
    // engine so it runs fully offline. Swap in a real engine (ktor-client-cio/okhttp)
    // for live calls.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.mock)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.mattramotar.inferencestore.samples.notes.MainKt")
}
