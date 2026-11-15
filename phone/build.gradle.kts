import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Renders Compose screens to PNGs on the JVM (screenshot tests, no emulator needed).
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.castlite.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.castlite.phone"
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
        // android.util.Log etc. return defaults instead of throwing in JVM unit tests.
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
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Tiny embedded HTTP server that streams the picked files to the TV.
    implementation(libs.nanohttpd)
    // Phone <-> CastLite TV app protocol (shared with the tv module).
    implementation(project(":protocol"))
    // Self-update from GitHub releases (shared with the other app).
    implementation(project(":updater"))
    // Thumbnails for the queue (photos and video frames).
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    // MediaSession: lock-screen controls and volume keys → TV volume while the phone is locked.
    implementation(libs.androidx.media)

    testImplementation(libs.junit)
}
