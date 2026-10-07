plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.opennomi.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = providers.gradleProperty("OPENNOMI_APPLICATION_ID").orElse("com.opennomi.android.voicetest").get()
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("OPENNOMI_VERSION_CODE").orElse("51").get().toInt()
        versionName = providers.gradleProperty("OPENNOMI_VERSION_NAME").orElse("0.51.0").get()

        ndk { abiFilters += providers.gradleProperty("OPENNOMI_ABIS").orElse("arm64-v8a").get().split(",") }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("legacy") {
            val store = providers.gradleProperty("OPENNOMI_KEYSTORE").orNull
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = providers.gradleProperty("OPENNOMI_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("OPENNOMI_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("OPENNOMI_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!providers.gradleProperty("OPENNOMI_KEYSTORE").orNull.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("legacy")
            }
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation(files("libs/tesseract4android-4.9.0.aar"))
    implementation(files("libs/vosk-android-0.3.75.aar", "libs/jna-5.18.1.aar"))
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
