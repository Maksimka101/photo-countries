plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {
        namespace = "dev.photocountries.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    // Desktop-цель нужна прежде всего ради тестов: на телефоне они идут на JVM.
    jvm()

    // jvmShared — код на java.*, общий для android и desktop.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                // withAndroidTarget() не видит цель нового KMP-плагина AGP — отбираем по типу платформы.
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            implementation(libs.cmp.resources)
            implementation(libs.ktor.client.core)
            // Показ фото: загрузка, кэш и декодирование; сеть — через тот же Ktor.
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Движок Ktor у каждой платформы свой, поверх её же HTTP-стека; HttpClient() находит его сам.
        androidMain.dependencies {
            // OkHttp, а не движок на HttpURLConnection: тот роняет приложение при отмене загрузки на середине
            // (фото прокрутили раньше, чем оно скачалось).
            implementation(libs.ktor.client.okhttp)
            // Запрос разрешения на галерею из Compose; библиотека и так есть в приложении.
            implementation(libs.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.java)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.photocountries.shared"
}

compose.desktop {
    application {
        mainClass = "dev.photocountries.MainKt"
    }
}
