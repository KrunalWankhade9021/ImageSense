import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ── Version derived from git (single source of truth) ────────────────────────
// versionName  = latest tag with the leading "v" stripped (e.g. v0.2.0 -> 0.2.0).
//                Falls back to "0.0.0-dev" when there is no tag (local dev builds).
// versionCode  = number of commits on HEAD — monotonically increases per commit,
//                so every release built off a later commit gets a higher code.
fun git(vararg args: String): String? = try {
    val out = ByteArrayOutputStream()
    val result = exec {
        commandLine("git", *args)
        standardOutput = out
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    if (result.exitValue == 0) out.toString().trim().ifEmpty { null } else null
} catch (e: Exception) {
    null
}

val gitVersionName: String =
    (git("describe", "--tags", "--abbrev=0")?.removePrefix("v")) ?: "0.0.0-dev"
val gitVersionCode: Int =
    git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

android {
    namespace = "com.nlphotos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nlphotos"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Ship only arm64 native libs (ONNX Runtime). Covers virtually all modern
        // Android phones and avoids bundling x86/armeabi variants we don't need.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    // Keep .onnx assets uncompressed so ONNX Runtime can mmap them
    androidResources {
        noCompress += listOf("onnx")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
