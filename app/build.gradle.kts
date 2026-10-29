plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkVer = (project.findProperty("desktop.sdk") as String).toInt()

android {
    namespace = "com.android.desktoplauncher"
    compileSdk = sdkVer

    defaultConfig {
        applicationId = "com.android.desktoplauncher"
        minSdk = 31
        targetSdk = sdkVer
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
