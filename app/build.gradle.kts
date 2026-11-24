plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.gms.google-services")
}

// ---------------------------------------------------------------------------
// THE ONE PLACE TO RENAME THE APP.
// This value feeds both the Kotlin constant (AppConfig.APP_NAME via BuildConfig)
// and the Android launcher label (@string/app_name via resValue).
// ---------------------------------------------------------------------------
val mobiusAppName = "MOBIUS"

android {
    namespace = "com.mobius"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobius"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "APP_NAME", "\"$mobiusAppName\"")
        resValue("string", "app_name", mobiusAppName)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room 2.8.5 bundles kotlinx-metadata-jvm that can only read Kotlin metadata
// up to 2.3.0, but the resolved kotlin-stdlib is 2.4.0 (metadata 2.4.0, pulled
// in by the Nov-2026 Firebase/ML Kit libs). Room's kapt processor walks every
// @Entity's superclass chain (kotlin.Any lives in stdlib), so stdlib MUST be
// <= 2.3.x. The Kotlin 2.4.21 compiler reads older stdlib metadata fine.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")
    }
}

dependencies {
    // --- AndroidX / Compose ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1") // setViewTreeSavedStateRegistryOwner (overlay Compose host)

    // --- Local storage ---
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Background / coroutines ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- Firebase AI Logic (hybrid on-device + cloud) + App Check ---
    // Versions taken from the official Firebase hybrid-inference setup page (Nov 2026).
    // The on-device SDK is not in the Firebase BoM yet, so versions are pinned explicitly.
    implementation("com.google.firebase:firebase-ai:18.2.0")
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08")
    implementation("com.google.firebase:firebase-appcheck-debug:20.1.0")
}
