import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.nodepass.somewhere"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "eu.nodepass.somewhere"

        // Nowhere's authentication tag is bound to a TLS exporter, and
        // android.net.ssl.SSLSockets.exportKeyingMaterial() is public API only
        // from API 31. Rather than give up API 26-30 for one 32-byte call, the
        // exporter sits behind KeyingMaterialExporter: the platform API on 31+,
        // Conscrypt below it. See docs/adr-0001-tls-exporter.md.
        minSdk = 26
        targetSdk = 36

        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Two ABIs, for two different reasons: arm64-v8a is what physical
            // devices and local emulators need, x86_64 is what AVDs on x86 CI
            // runners need. The donor project ships only the former.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProperties.getProperty("KEYSTORE_FILE", "../keystore.jks"))
            storePassword = localProperties.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProperties.getProperty("KEY_ALIAS", "release")
            keyPassword = localProperties.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") { kotlin.srcDir("src/main/kotlin") }
        getByName("test") { kotlin.srcDir("src/test/kotlin") }
        getByName("androidTest") { kotlin.srcDir("src/androidTest/kotlin") }
    }

    // The lwIP / TUN native layer is inherited from Anywhere-Android at L1.
    // Until then there is no JNI source set, so no externalNativeBuild block.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
