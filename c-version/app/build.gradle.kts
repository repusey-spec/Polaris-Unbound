plugins {
    id("com.android.application")
}

android {
    namespace = "com.polaris.cprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.polaris.cprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "C02-ytm-song-match"
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
