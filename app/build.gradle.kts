plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val contentRepo = System.getenv("GITHUB_REPOSITORY") ?: ""

android {
    namespace = "com.faithscroll.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.faithscroll.app"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CONTENT_REPO", "\"$contentRepo\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/faithscroll.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "faithscroll"
            keyAlias = "faithscroll"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "faithscroll"
        }
    }

    buildTypes {
        debug {
            // The test emulator is x86_64.
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            // 64-bit ARM: every modern phone. Leaving out other CPU types keeps the download much smaller.
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.testLogging { showStandardStreams = true; events("passed", "failed", "skipped") } }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Native video playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")
    // Finds the direct stream URLs for videos (same library the NewPipe app uses)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Proof-of-origin tokens (YouTube's bot check), generated in a hidden WebView like NewPipe does
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device language detection (offline), so videos match the app language
    implementation("com.google.mlkit:language-id:17.0.6")
    // On-device translation for subtitles
    implementation("com.google.mlkit:translate:17.0.3")
    // Offline speech recognition: makes subtitles for videos without captions
    // Opens ChatGPT in a browser tab for "What do they mean here?"
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // Optional offline AI: a small language model running on the phone
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Diagnostic tests (run in the cloud build against real videos and the real AI)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
