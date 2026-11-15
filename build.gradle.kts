// Top-level build file. Plugins are declared here and applied in each module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.paparazzi) apply false
}

/*
 * Versioning shared by the phone and TV apps (they're always released together).
 * On GitHub Actions every run gets a higher versionCode (100 + run number), which is
 * what the in-app updater compares. Local builds are "dev" builds with a low code, so
 * they always see the latest CI release as an update.
 */
val ciRunNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
extra["castliteVersionCode"] = if (ciRunNumber != null) 100 + ciRunNumber else 3
extra["castliteVersionName"] = if (ciRunNumber != null) "1.1.$ciRunNumber" else "1.1.0-dev"

/** Where the apps look for updates: the newest GitHub release of this repository. */
val updateRepo: String = providers.gradleProperty("castlite.updateRepo").orNull
    ?: System.getenv("GITHUB_REPOSITORY")
    ?: "Liebe0/Castlite"
extra["castliteUpdateManifestUrl"] = "https://github.com/$updateRepo/releases/latest/download/update.json"
