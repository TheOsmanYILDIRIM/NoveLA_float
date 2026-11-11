plugins {
    alias(libs.plugins.noveldokusha.android.library)
    alias(libs.plugins.noveldokusha.android.compose)
}

android {
    namespace = "my.noveldokusha.reader"

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.coreui)
    implementation(projects.data)
    implementation(projects.scraper)
    implementation(projects.features.settings)
    implementation(projects.navigation)
    implementation(projects.tooling.localDatabase)
    implementation(projects.tooling.textToSpeech)
    implementation(projects.tooling.textTranslator.domain)
    implementation(projects.networking)

    // Native CPU inference; the ONNX checkpoint is bundled into the APK at build time.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    implementation(libs.material)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.compose.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.compose.androidx.activity)
    implementation(libs.compose.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.compose.material3.android)
    implementation(libs.coil)
    implementation(libs.compose.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.timber)
    implementation(libs.subsampling.scale.image.view)

    // Kotlin reflection for dynamic batch translator lookup
    implementation(kotlin("reflect"))

    testImplementation(libs.test.junit)
    testImplementation(libs.test.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}


// Package the model *during APK creation*, never on the user's phone.
// This fails the build instead of silently shipping a fake/offline-empty feature.
val prepareNovelLexAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "novellex/prepare_assets.py")
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareNovelLexAssets)
}
