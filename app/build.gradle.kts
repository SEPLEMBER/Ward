plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.syndes.terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.syndes.terminal"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // лучше удалить `signingConfig = null` — подпись задаётся отдельно при релизе
        }
    }

    // Включаем viewBinding — удобно и безопасно для Kotlin UI-кода
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // На случай конфликтов при пакетировании (иногда возникает с bcprov)
    /*
    packagingOptions {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    */

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Core ktx — рекомендую добавить
    implementation("androidx.core:core-ktx:1.11.0") // или alias(libs.core.ktx) если есть в catalog

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.bcprov.jdk15on)

    // На minSdk >= 21 библиотека multidex обычно не нужна. Оставлять — по желанию.
    implementation(libs.multidex)
    implementation(libs.recyclerview)

    // --- Kotlin coroutines ---
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // --- Lifecycle (для lifecycleScope) ---
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // --- Preferences ---
    implementation("androidx.preference:preference-ktx:1.2.1")

    // --- Для работы с Uri (AndroidX) ---
    implementation("androidx.documentfile:documentfile:1.0.1")

    // --- Activity Result API для ActivityResultLauncher ---
    implementation("androidx.activity:activity-ktx:1.9.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
