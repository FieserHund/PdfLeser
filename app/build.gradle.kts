plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versionsnummer steigt automatisch (Minuten seit 1970) – lokal wie auf GitHub.
val autoVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: (System.currentTimeMillis() / 60_000L).toInt()
// Auf GitHub wird ausdrücklich mit dem hinterlegten Schlüssel signiert (Pfad per -PsigningStore).
// Lokal (Android Studio) bleibt es beim normalen Debug-Schlüssel des Laptops – das ist derselbe.
val sharedKeystore: String? = (project.findProperty("signingStore") as String?)?.takeIf { it.isNotBlank() }
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

    signingConfigs {
        sharedKeystore?.let { path ->
            create("shared") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
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
