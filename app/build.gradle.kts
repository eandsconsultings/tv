plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "tv.guide.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.guide.app"
        minSdk = 22
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.$buildNumber"
    }
    // One fixed signing key, so every new build installs over the last one.
    // The key file is created once by the build workflow and kept in its private cache, never in the repository.
    signingConfigs {
        create("onyx") {
            storeFile = rootProject.file("signing/onyx.keystore")
            storePassword = "onyxtv"
            keyAlias = "onyx"
            keyPassword = "onyxtv"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("onyx")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
