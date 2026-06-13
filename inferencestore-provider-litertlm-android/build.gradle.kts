plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// LiteRT-LM is an Android/JVM runtime, so this adapter declares only those targets.
val androidEnabled = System.getProperty("inferencestore.androidEnabled") == "true"

if (androidEnabled) {
    apply(plugin = libs.plugins.android.library.get().pluginId)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()
    if (androidEnabled) {
        androidTarget()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":inferencestore-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

if (androidEnabled) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "dev.mattramotar.inferencestore.provider.litertlm"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
    }
}
