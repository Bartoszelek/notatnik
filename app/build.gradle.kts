plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.notatnik"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.notatnik"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("main") {
            storeFile = file("notatnik.keystore")
            storePassword = "notatnik1"
            keyAlias = "notatnik"
            keyPassword = "notatnik1"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("main") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("main")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.sun.mail:android-mail:1.6.7")
    // rozpoznawanie mowy offline (Whisper, VAD, odciski głosu) – AAR pobierany w GitHub Actions
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("com.sun.mail:android-activation:1.6.7")
}
