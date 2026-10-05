// The One Run – Wear OS-app voor Galaxy Watch 4 en nieuwer.
// LET OP: applicationId, versie en signing zijn GELIJK aan runcoach/build.gradle.kts.
// Telefoon en horloge kunnen alleen met elkaar praten als beide APK's dezelfde
// applicationId hebben en met dezelfde vaste sleutel zijn ondertekend.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gmailorg.runcoach.wear"
    compileSdk = 35

    val appVersionCode = (project.findProperty("appVersionCode")?.toString()
        ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

    defaultConfig {
        applicationId = "com.gmailorg.runcoach"
        minSdk = 30
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // zelfde versie als in runcoach
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
