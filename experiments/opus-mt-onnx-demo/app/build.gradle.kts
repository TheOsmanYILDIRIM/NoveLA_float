plugins { id("com.android.application") }

android {
    namespace = "com.osman.opusdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.osman.opusdemo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-onnx-experiment"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
