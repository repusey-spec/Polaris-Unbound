plugins { id("com.android.application") }

android {
    namespace = "com.polarisunbound.ytminspector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.polarisunbound.ytminspector"
        minSdk = 26
        targetSdk = 33
        versionCode = 1003
        versionName = "1.a03"
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
