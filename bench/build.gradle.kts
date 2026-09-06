plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Standalone benchmark APK for remote device farms.
 *
 * Samsung Remote Test Lab gives no adb: you upload an APK, launch it and read a
 * laggy remote screen. So this is an app, not an instrumentation test — it
 * auto-starts, keeps the screen on, and rewrites the on-screen table after every
 * pass so any screenshot is a valid datapoint for its elapsed time.
 *
 * Two flavors:
 *   fat  — bundles ggml-base.en-q5_1 (~70 MB APK), nothing to download
 *   slim — fetches the model on first launch (~13 MB APK), needs network
 */

// Assets are copied from :app rather than duplicated in the repo: base.en alone
// is 57 MB and is already tracked once.
val appAssets = "${projectDir}/../app/src/main/assets"
val ttsAssets = "${projectDir}/../app/src/androidTest/assets/tts"
// Plain File, not a Provider: the Android SourceSet API rejects Providers.
val benchAssetsRoot = File(layout.buildDirectory.get().asFile, "generated/benchAssets")
val commonAssetsDir = File(benchAssetsRoot, "common")
val fatAssetsDir = File(benchAssetsRoot, "fat")

val copyCommonBenchAssets by tasks.registering(Copy::class) {
    into(commonAssetsDir)
    from(appAssets) {
        include("medical_intent.onnx", "silero_vad.onnx", "vocab.txt",
                "model_config_medical.json", "labels_medical.txt")
    }
    from(ttsAssets) { into("tts") }
}

val copyFatBenchAssets by tasks.registering(Copy::class) {
    into(fatAssetsDir)
    from(appAssets) { include("ggml-base.en-q5_1.bin") }
}

android {
    namespace = "com.khateev.medvoice.bench"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.khateev.medvoice.bench"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Farm uploads are size-limited; one ABI is enough for any modern phone.
        ndk { abiFilters.add("arm64-v8a") }
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "models"
    productFlavors {
        create("fat") {
            dimension = "models"
            buildConfigField("String", "MODEL_URL", "\"\"")
            buildConfigField("boolean", "BUNDLED_MODEL", "true")
        }
        create("slim") {
            dimension = "models"
            applicationIdSuffix = ".slim"
            buildConfigField(
                "String", "MODEL_URL",
                "\"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin\""
            )
            buildConfigField("boolean", "BUNDLED_MODEL", "false")
        }
    }

    sourceSets {
        getByName("main") { assets.srcDir(commonAssetsDir) }
        getByName("fat") { assets.srcDir(fatAssetsDir) }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    // Same as :app — whisper.cpp mmaps the model through openFd, which only
    // works on assets stored uncompressed.
    androidResources {
        noCompress += listOf("bin", "onnx")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copyCommonBenchAssets, copyFatBenchAssets)
}

dependencies {
    // The production chain: WhisperEngine, SileroVad, MedIntentClassifier.
    implementation(project(":voice-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
