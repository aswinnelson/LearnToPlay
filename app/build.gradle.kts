import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.learntoplay.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.learntoplay.app"
        minSdk = 26
        targetSdk = 34
        // Bumped from 1 / "0.1.0-mvp" — this build adds Crashlytics, the Admin Dashboard /
        // Device Settings split, two-way remote control (add time / lock now), and batch AI
        // question generation on top of the original MVP feature set.
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        // AGP 8.0+ stopped generating BuildConfig by default — needed explicitly here since
        // AdminDashboardScreen checks BuildConfig.DEBUG to show the debug-only test-crash card.
        buildConfig = true
    }
    // kotlinCompilerExtensionVersion is no longer set here — since Kotlin 2.0, the Compose
    // compiler ships as part of the Kotlin toolchain itself via the
    // org.jetbrains.kotlin.plugin.compose plugin above, which is kept in lockstep with the
    // Kotlin version declared in the root build.gradle.kts.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Replaces the old (now-removed) android.kotlinOptions { jvmTarget = "17" } block — the
// Kotlin Gradle plugin moved this into its own top-level `kotlin { compilerOptions { ... } }`
// extension as of Kotlin 2.x.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Needed to host a ComposeView inside a raw WindowManager overlay window (LockOverlayManager),
    // which sits outside any Activity and so needs these ViewTree*Owner extension functions.
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // Room (local-first persistence — no login, no cloud account for MVP). Bumped from 2.6.1 to
    // 2.8.5 (latest stable) because 2.6.1 predates Room's KSP2 support — it was hitting a known
    // KSP2 bug ("unexpected jvm signature V") that only newer Room releases avoid, since Room
    // only added real Kotlin 2.0 / KSP2 compatibility starting at 2.7.0.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Security (PIN hashing)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // On-device text recognition for the "scan a textbook photo" question-entry flow
    // (Stage C). Runs entirely on-device via a bundled/auto-downloaded model — no image
    // or text ever leaves the phone, consistent with the app's local-first design.
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // On-device LLM (MediaPipe LLM Inference API) that drafts multiple-choice options from a
    // question's text. The model file itself (~500MB+, e.g. Gemma 3 1B) is NOT bundled here —
    // it's pushed once via `adb push` to /data/local/tmp/llm/ on a test device and loaded from
    // that fixed path, so no network call happens at build or run time for this dependency.
    // Google's own docs note this doesn't reliably run on emulators — real-device only.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // Remote parent monitoring: syncs a narrow read-only snapshot (time bank, gated apps, quiz
    // history — never the PIN or question content) to Firestore so a parent's own separate
    // phone can check on it. The one deliberate exception to this app's local-first design,
    // and opt-in in effect (nothing syncs until a parent generates a pairing code).
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    // Crash reporting — see the crashlytics plugin in the root build.gradle.kts. Analytics is
    // included because Crashlytics relies on it under the hood for breadcrumbs (the trail of
    // recent app activity attached to a crash report), not because this app otherwise wants
    // usage analytics.
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
    // Provides the `.await()` suspend extension used to bridge Firebase's Task API into
    // coroutines (kept intentionally separate from Firebase's own artifacts).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
