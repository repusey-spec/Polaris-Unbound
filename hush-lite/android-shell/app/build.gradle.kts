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
        applicationId = "app.hush.music.lite.test"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "hush-lite-internal.01"
    }
}
dependencies {
    implementation(project(":core"))
    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
}
