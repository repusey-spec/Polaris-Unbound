plugins { id("com.android.application") }

android {
    namespace = "com.polarisunbound.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.polarisunbound.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
