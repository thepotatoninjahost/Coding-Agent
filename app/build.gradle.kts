plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val resolvedVersionCode = ciBuildNumber ?: 2

android {
    namespace = "com.codingagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codingagent"
        // Galaxy S25 / Android 14+. java.time and java.nio.file are used throughout;
        // minSdk 24 made lint report those as NewApi (~73 errors) and is not the product target.
        minSdk = 34
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = "0.1.$resolvedVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        vectorDrawables { useSupportLibrary = true }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests.all {
            // The OpenRouter runner talks to the network and needs the owner's key.
            // It stays out of the normal test run. scripts/run_openrouter_agent.sh opts in.
            if (!project.hasProperty("runOpenRouter")) {
                it.exclude("**/OpenRouterAgentRunner*")
            }
        }
    }

    // The same debug key is in the repo so every CI build installs over the last one.
    // Release signing is optional and reads a keystore that is not in this repository.
    val releaseKeystorePath = System.getenv("CODING_AGENT_KEYSTORE")
    val releaseStorePassword = System.getenv("CODING_AGENT_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("CODING_AGENT_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("CODING_AGENT_KEY_PASSWORD")
    val releaseSigningReady = !releaseKeystorePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseKeystorePath).isFile

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-rules.pro")
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    // Required so registerForActivityResult on FragmentActivity passes lint (min 1.3.0).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Owner gate: approval signs with an auth-per-use Keystore key inside BiometricPrompt.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Tink backs KeystoreSecretStore (AndroidKeysetManager AEAD). Do not remove
    // until that store no longer imports com.google.crypto.tink.
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    // quickjs-kt's published Android libraries all require compileSdk 36.
    // This project stays on compileSdk 35 (AGP 8.7). JavaScript runs in Rhino.
    implementation("org.mozilla:rhino:1.7.15")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
}
