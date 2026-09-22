plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versionsnummer steigt automatisch (Minuten seit 1970) – lokal wie auf GitHub.
val autoVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: (System.currentTimeMillis() / 60_000L).toInt()
// GitHub-Repository für Updates („benutzer/repo“), siehe gradle.properties
val updateRepo: String = (project.findProperty("updateRepo") as String?).orEmpty().trim()

android {
    namespace = "de.pdfleser.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.pdfleser.app"
        minSdk = 29
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = "1.2 (Build $autoVersionCode)"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // PDF-Textextraktion (reiner Java-Port von Apache PDFBox)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
