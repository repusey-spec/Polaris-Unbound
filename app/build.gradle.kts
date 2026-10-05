plugins { id("com.android.application") }

android {
    namespace = "com.polarisunbound.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.polarisunbound.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 37
        versionName = "0.37"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file(".github/polaris-debug.keystore")
            storePassword = "android"
            keyAlias = "polarisdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
}
