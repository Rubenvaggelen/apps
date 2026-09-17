plugins {
    id("com.android.application")
}

android {
    namespace = "com.theone.mediaplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.theone.mediaplayer"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-test"
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
