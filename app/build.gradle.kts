plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.myos.liquidlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.myos.liquidlab"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.3.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
