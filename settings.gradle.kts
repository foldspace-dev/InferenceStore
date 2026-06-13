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
    ":samples:notes-summary",
)
