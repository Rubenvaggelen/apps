plugins {
    id("com.android.application")
}

val mediaPlayerKeystorePath = System.getenv("MEDIA_PLAYER_KEYSTORE_PATH")
val mediaPlayerKeystorePassword = System.getenv("MEDIA_PLAYER_KEYSTORE_PASSWORD")
val mediaPlayerKeyAlias = System.getenv("MEDIA_PLAYER_KEY_ALIAS")
val mediaPlayerKeyPassword = System.getenv("MEDIA_PLAYER_KEY_PASSWORD")

android {
    namespace = "com.theone.mediaplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.theone.mediaplayer"
        minSdk = 23
        targetSdk = 36
        versionCode = 2100
        versionName = "2.1.0-ui-subs"
    }

    signingConfigs {
        if (
            !mediaPlayerKeystorePath.isNullOrBlank() &&
            !mediaPlayerKeystorePassword.isNullOrBlank() &&
            !mediaPlayerKeyAlias.isNullOrBlank() &&
            !mediaPlayerKeyPassword.isNullOrBlank()
        ) {
            create("mediaPlayerRelease") {
                storeFile = file(mediaPlayerKeystorePath)
                storePassword = mediaPlayerKeystorePassword
                keyAlias = mediaPlayerKeyAlias
                keyPassword = mediaPlayerKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("mediaPlayerRelease")?.let {
                signingConfig = it
            }
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
