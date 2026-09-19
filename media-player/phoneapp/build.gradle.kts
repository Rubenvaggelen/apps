plugins {
    id("com.android.application")
}

android {
    namespace = "com.theone.mediaplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.theone.mediaplayer.stream"
        minSdk = 23
        targetSdk = 36
        versionCode = 1001
        versionName = "1.0.1-stream"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("../app/src/main/java")
            res.srcDirs("../app/src/main/res")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
}
