plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "my.novela.ematest"
    compileSdk = 35
    defaultConfig { applicationId = "my.novela.ematest"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
}
dependencies { implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0") }
