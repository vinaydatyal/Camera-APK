plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vlogcam.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vlogcam.app"
        minSdk = 26
        targetSdk = 36
        // CI sets GITHUB_RUN_NUMBER so every build has a higher version than the last.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
        buildConfigField(
            "String",
            "UPDATE_BASE_URL",
            "\"https://github.com/vinaydatyal/Camera-APK/releases/download/latest-build/\""
        )
    }

    // Fixed personal signing key so new builds install as updates over old ones.
    signingConfigs {
        create("personal") {
            storeFile = file("vlogcam.keystore")
            storePassword = "vlogcam123"
            keyAlias = "vlogcam"
            keyPassword = "vlogcam123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // On-device person segmentation for the dual "Cutout" layout.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
