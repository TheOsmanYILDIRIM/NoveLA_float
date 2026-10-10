plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.noveldokusha.android.compose)
}

android {
    namespace = "my.noveldokusha.tooling.texttospeech"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.test.junit)

    implementation(libs.timber)
    // Native Android CPU runtime for the bundled FP32 EMA Lightning ONNX graphs.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
}