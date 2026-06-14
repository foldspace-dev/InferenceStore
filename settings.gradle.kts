import java.io.File

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "inferencestore"

include(
    ":inferencestore-core",
    ":inferencestore-testkit",
    ":inferencestore-provider-openai-compatible",
    ":inferencestore-provider-litertlm-android",
    ":inferencestore-provider-firebase-android",
    ":inferencestore-store-sqldelight",
    ":inferencestore-monitor-opentelemetry",
    ":samples:notes-summary",
    ":samples:cross-platform",
    ":samples:validation-demo",
)

// The Android target is enabled only when a real SDK is configured: an
// ANDROID_HOME / ANDROID_SDK_ROOT pointing at an existing directory, or a
// local.properties that actually defines sdk.dir. Computed once here and shared
// with every module via a system property to avoid false-positive enablement
// (e.g. an empty local.properties or a stale env var pointing nowhere).
System.setProperty(
    "inferencestore.androidEnabled",
    run {
        val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val fromEnv = env != null && File(env).isDirectory
        val localProps = File(rootDir, "local.properties")
        val fromLocalProps = localProps.exists() && runCatching {
            val sdkDir = java.util.Properties()
                .apply { localProps.inputStream().use { load(it) } }
                .getProperty("sdk.dir")
            sdkDir != null && File(sdkDir).isDirectory
        }.getOrElse { false }
        (fromEnv || fromLocalProps).toString()
    },
)
