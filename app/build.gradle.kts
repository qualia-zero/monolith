plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.monolith.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.monolith.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.3.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        val releaseKs = rootProject.file("keystore/release.keystore")
        if (releaseKs.exists()) {
            getByName("debug") {
                storeFile = releaseKs
                storePassword = "android"
                keyAlias = "android"
                keyPassword = "android"
            }
            create("release") {
                storeFile = releaseKs
                storePassword = "android"
                keyAlias = "android"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:vfs"))
    implementation(project(":core:media"))
    implementation(project(":feature:explorer"))
    implementation(project(":feature:viewers"))
    implementation(project(":feature:player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
