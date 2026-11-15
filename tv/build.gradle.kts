import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Renders Compose screens to PNGs on the JVM (screenshot tests, no emulator needed).
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "com.castlite.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.castlite.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = rootProject.extra["castliteVersionCode"] as Int
        versionName = rootProject.extra["castliteVersionName"] as String
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${rootProject.extra["castliteUpdateManifestUrl"]}\"")
    }

    signingConfigs {
        // A fixed debug key committed to the repo, so every CI build has the same
        // signature and can be installed over the previous one without uninstalling.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // android.* stubs (e.g. Uri) return defaults instead of throwing in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.tv.material)
    implementation(libs.compose.material.icons)

    // Phone <-> TV protocol: JSON models + the HTTP API server.
    implementation(project(":protocol"))
    // Self-update from GitHub releases (shared with the other app).
    implementation(project(":updater"))
    // Video and audio playback.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Photos (with EXIF rotation, crossfade and caching).
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    testImplementation(libs.junit)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
