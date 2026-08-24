import java.util.Properties

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) load(file.inputStream())
    }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "eu.nodepass.somewhere"
    compileSdk {
        version =
            release(36) {
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
                "proguard-rules.pro",
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

    // The conformance vectors are the acceptance test for the protocol layer, so
    // unit tests consume the same file the Python checker does rather than a
    // transcribed copy. Copied in rather than read by relative path: a test that
    // reaches outside the module breaks when the working directory changes, and
    // CI runs Gradle from the repository root.
    sourceSets {
        getByName("test") {
            resources.srcDir(rootProject.file("conformance/vectors"))
        }
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

    // Nowhere's authentication is bound to a TLS exporter, and the platform only
    // exposes one from API 31. Conscrypt carries the same call down to our
    // minSdk. See docs/adr-0001-tls-exporter.md.
    implementation(libs.conscrypt.android)

    testImplementation(libs.junit)
    // Needed by the tests that drive the repository, which is suspend-shaped
    // because a subscription fetch is a network call.
    testImplementation(libs.coroutines.test)
    // The JVM build of the same library, so the exporter can be checked against
    // a real TLS handshake on the host rather than only on a device.
    testImplementation(libs.conscrypt.openjdk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ── Quality gates ───────────────────────────────────────────────────────────
// These land in M0, before any protocol code, so that correctness is enforced
// by automation from the first protocol commit rather than retrofitted later.

ktlint {
    android = true
    ignoreFailures = false
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

kover {
    reports {
        // The gate exists for the protocol layer: that is the part where being
        // wrong means being wrong on the wire. Kover 0.9 only supports filters
        // at the report level, so the report *is* the protocol layer. UI and
        // generated code are deliberately outside it — one coverage number
        // applied uniformly just gets gamed with trivial tests.
        filters {
            includes {
                classes(
                    "eu.nodepass.somewhere.protocol.*",
                    "eu.nodepass.somewhere.subscription.*",
                )
            }
            excludes {
                classes(
                    "eu.nodepass.somewhere.BuildConfig",
                    "*.R",
                    "*.R$*",
                )
            }
        }

        verify {
            rule("Protocol layer line coverage") {
                bound {
                    minValue = 90
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
        }
    }
}

// ── Compile/runtime classpath consistency ───────────────────────────────────
// Added after a real failure: `FlowRow` compiled against foundation-layout
// 1.7.2 and shipped against 1.9.2, whose signature differed, so the Diagnostics
// tab died with NoSuchMethodError the first time it was opened. Every existing
// gate was green — the build, ktlint, lint, and 253 unit tests — because the
// compiler validates against one classpath and the device runs another.
//
// Nothing else in this project notices that, and the failure mode is a crash on
// a screen nobody happened to open before a release.

/** Every external module on a configuration, as `group:name` -> version. */
fun resolvedVersions(configurationName: String): Provider<Map<String, String>> =
    configurations.named(configurationName).map { configuration ->
        configuration.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .associate { "${it.group}:${it.module}" to it.version }
    }

abstract class ClasspathConsistencyTask : DefaultTask() {
    @get:Input
    abstract val compileVersions: MapProperty<String, String>

    @get:Input
    abstract val runtimeVersions: MapProperty<String, String>

    @get:Input
    abstract val variant: Property<String>

    @TaskAction
    fun check() {
        val compile = compileVersions.get()
        val runtime = runtimeVersions.get()
        val skewed =
            compile
                .filterKeys { it in runtime }
                .filter { (module, version) -> runtime.getValue(module) != version }
                .toSortedMap()

        if (skewed.isEmpty()) {
            logger.lifecycle(
                "${variant.get()}: ${compile.size} modules, compile and runtime agree on every one.",
            )
            return
        }

        val detail =
            skewed.entries.joinToString("\n") { (module, compiled) ->
                "  $module: compiled against $compiled, packaged ${runtime.getValue(module)}"
            }
        throw GradleException(
            "${skewed.size} module(s) differ between the ${variant.get()} compile and runtime " +
                "classpaths:\n$detail\n\n" +
                "The compiler validates calls against the first version and the device runs the " +
                "second. Where a signature changed between them the result is a NoSuchMethodError " +
                "at the moment that code first runs — with the build, lint and every unit test " +
                "green. Align the versions (usually by moving the BOM, not by pinning one module).",
        )
    }
}

val classpathConsistencyTasks =
    listOf("Debug" to "debug", "Release" to "release").map { (capitalised, lowercase) ->
        tasks.register<ClasspathConsistencyTask>("checkClasspathConsistency$capitalised") {
            group = "verification"
            description = "Fails when $lowercase compiles against a different version than it ships."
            variant.set(lowercase)
            compileVersions.set(resolvedVersions("${lowercase}CompileClasspath"))
            runtimeVersions.set(resolvedVersions("${lowercase}RuntimeClasspath"))
        }
    }

tasks.register("checkClasspathConsistency") {
    group = "verification"
    description = "Compile/runtime version skew across every variant."
    dependsOn(classpathConsistencyTasks)
}

tasks.named("check") { dependsOn(classpathConsistencyTasks) }
