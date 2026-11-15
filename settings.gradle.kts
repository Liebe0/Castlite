pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CastLite"

// Sender app that runs on the phone.
include(":phone")
// Receiver app that runs on Android TV.
include(":tv")
// Shared phone <-> TV receiver protocol (pure Kotlin, no Android).
include(":protocol")
// Self-updater shared by both apps (Android library).
include(":updater")
