plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.udiweinstein.reframelite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.udiweinstein.reframelite"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed key in the repo so every build installs as an update over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = file("reframe.keystore")
            storePassword = "reframe123"
            keyAlias = "reframe"
            keyPassword = "reframe123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No external libraries: Android SDK + Kotlin stdlib only.
