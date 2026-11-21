plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentranslator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentranslator.app"
        minSdk = 29        // Android 10
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // OCR (английский текст с экрана), работает офлайн
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Перевод EN -> RU офлайн (модель скачивается при первом запуске)
    implementation("com.google.mlkit:translate:17.0.3")
    // Для онлайн-режима (Google Cloud Translation)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
