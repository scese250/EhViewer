plugins {
    alias(libs.plugins.ehviewer.android.application)
}

android {
    namespace = "com.hippo.ehviewer.translatortest"

    defaultConfig {
        applicationId = "com.hippo.ehviewer.translatortest"
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = File(rootDir, "app/keystore/androidkey.jks")
            storePassword = "000000"
            keyAlias = "key0"
            keyPassword = "000000"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Google ML Kit Japanese Text Recognition (bundled model)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}
