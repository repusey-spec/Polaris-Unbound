plugins {
    id("com.android.application")
}

android {
    namespace = "com.polaris.cprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.polaris.cprobe.c02"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "C03-parse-and-diagnose"
    }

    // CI restores the repository's existing debug signing key before build.
    // Reusing the same debug key for later C02.x test APKs avoids another
    // incompatible-signature update failure.
    signingConfigs {
        create("persistentDebug") {
            storeFile = rootProject.file("../.github/polaris-debug.keystore")
            storePassword = "android"
            keyAlias = "polarisdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("persistentDebug")
        }
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
