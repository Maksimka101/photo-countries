plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.photocountries"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.photocountries"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Своего ключа пока нет: релиз подписан отладочным, поэтому ставится поверх debug-сборки без потери данных.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    // BOM только выравнивает версии androidx Compose: без него material-ripple отстаёт от ui/foundation.
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
}
