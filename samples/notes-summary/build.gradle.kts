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
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.mattramotar.inferencestore.samples.notes.MainKt")
}
