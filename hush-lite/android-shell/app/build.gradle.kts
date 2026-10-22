plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "app.hush.music.lite"
    compileSdk = 37
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
