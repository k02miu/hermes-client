plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "dev.kamiya.hermesmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kamiya.hermesmobile"
        minSdk = 31
        targetSdk = 36
        // CI passes the tag and run number; local builds keep the defaults.
        versionCode = System.getenv("HERMES_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("HERMES_VERSION_NAME") ?: "0.1.0"
    }

    // The release key stays out of the repo: CI decodes it from secrets, locally it lives in ~/.config/hermes-mobile.
    val keystore = System.getenv("HERMES_KEYSTORE_FILE")?.let(::file)
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("HERMES_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("HERMES_KEY_ALIAS") ?: "hermes-mobile"
            keyPassword = System.getenv("HERMES_KEY_PASSWORD") ?: System.getenv("HERMES_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        // A separate package so a dev build sits next to the release one instead of clashing over the signature.
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Hermes Dev"
        }
        release {
            manifestPlaceholders["appLabel"] = "Hermes"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markdown.m3)
    debugImplementation(libs.compose.ui.tooling)
}
