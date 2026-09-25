plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ps4bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ps4bridge"
        minSdk = 26
        // Deliberately 34: apps targeting 35+ get a ~6 h time limit on "dataSync" foreground services,
        // which would kill a very long transfer. This app is sideloaded, so it is not required to target 35.
        targetSdk = 34
        versionCode = 12
        versionName = "1.2"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
// No third-party dependencies on purpose: fewer things can break the build.
