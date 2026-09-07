import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val debugKeystoreFile = file("debug.keystore")
val debugKeystoreB64 = file("debug-signing.keystore.b64")
if (!debugKeystoreFile.exists() && debugKeystoreB64.exists()) {
    debugKeystoreFile.writeBytes(
        Base64.getDecoder().decode(debugKeystoreB64.readText().trim())
    )
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
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Keystore-backed encryption for SharedPreferences (LocalStore holds the model API key).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
