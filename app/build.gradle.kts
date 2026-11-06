import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Permanent CI debug signing -------------------------------------------
// GitHub-hosted runners are ephemeral: without this, every build would get a
// fresh debug key, so new APKs could not install over old ones (and the app's
// data would be wiped by the forced uninstall each time).
// The repo carries our own debug keystore as base64 (debug-keystore.b64 at
// the project root — the standard public debug credentials, not a secret).
// Decode it once per build and sign the debug variant with it, so the
// signature is stable forever:
// SHA-1: 1C:DC:B5:BC:60:C6:1E:C3:32:9A:1B:B2:23:B2:2C:9D:33:18:58:6B
val ciDebugKeystore: File? = run {
    val b64 = rootProject.file("debug-keystore.b64")
    if (!b64.exists()) return@run null
    val out = layout.buildDirectory.file("ci-debug.keystore").get().asFile
    if (!out.exists()) {
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(b64.readText()))
    }
    out
}

android {
    namespace = "app.sarhadsquad.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.sarhadsquad.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.0"
    }

    signingConfigs {
        if (ciDebugKeystore != null) {
            create("ciDebug") {
                storeFile = ciDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (ciDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
