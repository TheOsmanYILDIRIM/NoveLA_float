plugins { id("com.android.application") }

android {
    namespace = "com.osman.opusdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.osman.opusdemo"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.0-onnx-experiment"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("stableDemo") {
            val keyPath = System.getenv("OPUS_DEMO_KEYSTORE_PATH")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = "androidtest"
                keyAlias = "novela-ema-clone-demo"
                keyPassword = "androidtest"
                storeType = "pkcs12"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableDemo")
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
