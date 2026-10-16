plugins { id("com.android.application") }

android {
    namespace = "com.polarisunbound.ytminspector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.polarisunbound.ytminspector"
        minSdk = 26
        targetSdk = 33
        versionCode = 1002
        versionName = "1.a02"
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
