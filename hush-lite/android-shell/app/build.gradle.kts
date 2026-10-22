plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "app.hush.music.lite"
    compileSdk = 36
    // OkHttp DNS and JSpecify ship the same multi-release OSGi metadata.
    // These are JVM manifest resources, not Android runtime classes.
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    defaultConfig {
        applicationId = "app.hush.music.lite.hlt02.test"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "hush-lite-HLT02.02"
    }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    // Required by Dispatchers.Main used in MainActivity/MediaLibraryService.
    // Without this, an APK can compile but crash at initial scope.launch.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
