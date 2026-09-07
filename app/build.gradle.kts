plugins {
    id("com.android.application")
}

android {
    namespace = "com.gummytranslate.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gummytranslate.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.4.0-alpha01"

        ndk {
            abiFilters += listOf("arm64-v8a")
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
    }
}

dependencies {
    implementation(files("libs/nuisdk-release.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
