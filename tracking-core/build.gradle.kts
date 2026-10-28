import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.2.20"
}

group = "io.github.congtuandevmobile.tracker"
version = "0.0.1-beta"

kotlin {
    // Pure algorithms: no platform API, no third-party library. Android compiles
    // src/commonMain directly into the plugin; iOS gets the framework below.
    jvm() // fast unit tests on the development machine

    val xcf = XCFramework("TrackingCore")
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "TrackingCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
