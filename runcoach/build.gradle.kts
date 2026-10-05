// The One Run — losse hardloopcoach-app in de "apps"-repo.
// Gebruikt GEEN externe libraries, alleen standaard Android-API's.
//
// Signing en Java/Kotlin-instellingen zijn gelijk aan carradio/build.gradle, zodat de APK
// met dezelfde vaste sleutel wordt ondertekend en updates zonder "signature mismatch" werken.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gmailorg.runcoach"
    compileSdk = 35

    val appVersionCode = (project.findProperty("appVersionCode")?.toString()
        ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

    defaultConfig {
        applicationId = "com.gmailorg.runcoach"
        minSdk = 26
        targetSdk = 35
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
