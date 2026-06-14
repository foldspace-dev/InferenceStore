plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

val androidEnabled = System.getProperty("inferencestore.androidEnabled") == "true"

if (androidEnabled) {
    apply(plugin = libs.plugins.android.library.get().pluginId)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    if (androidEnabled) {
        androidTarget()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":inferencestore-core"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

if (androidEnabled) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "dev.mattramotar.inferencestore.provider.openai"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
    }
}
