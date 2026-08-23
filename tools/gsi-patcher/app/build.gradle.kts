plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.g7tabpro.gsipatch.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.g7tabpro.gsipatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so the APK is installable straight off the build:
            // this is a local utility, not a Play distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))
}
