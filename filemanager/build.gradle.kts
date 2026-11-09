plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkVer = (project.findProperty("desktop.sdk") as String).toInt()

android {
    namespace = "com.android.desktopfiles"
    compileSdk = sdkVer

    defaultConfig {
        applicationId = "com.android.desktopfiles"
        minSdk = 24
        targetSdk = sdkVer
        versionCode = 1
        versionName = "1.0"
    }

    // The same fixed signing key as the launcher, so both apps are signed alike and update in place.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Android 7-8 lack some newer Java classes (java.time, Optional, …) that libraries such as the SMB one may use
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    // the SMB library and BouncyCastle each carry the same housekeeping files
    packaging { resources { excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST") } }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Windows shares (SMB 2/3). The library brings its own BouncyCastle (MD4 / crypto that Android does not offer) and slf4j.
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // 7z, tar.bz2, tar.xz and tar.lz4 archives (zip and tar.gz are written by the app itself)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
