import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM library shared by the phone (client) and the TV (server).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.nanohttpd) // ReceiverApiServer extends NanoHTTPD
    testImplementation(libs.junit)
}
