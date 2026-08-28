import java.util.Properties
import java.util.zip.ZipFile

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) load(file.inputStream())
    }

// The QUIC stack's pinned versions, read from the one file that holds them.
// They reach the device through BuildConfig so that an instrumentation test can
// ask the *linked binary* what it is and compare. Two text files agreeing about
// a version number is not evidence about what will run.
val quicPins =
    rootProject
        .file("tools/quic/DEPENDENCIES")
        .readLines()
        .filter { it.contains("=") && !it.trimStart().startsWith("#") }
        .associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim() }

fun quicPin(name: String): String = quicPins[name] ?: error("tools/quic/DEPENDENCIES has no $name")

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

    // Pinned rather than left to AGP's default, because the default moves with
    // the plugin and a CI runner carries whatever NDKs its image happened to
    // ship. An unpinned toolchain is a native build that compiles differently
    // on two machines and is discovered when one of them crashes.
    ndkVersion = "28.2.13676358"

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

        // Where the device-side end-to-end tests find their host. Passed as
        // Gradle properties by `conformance/scripts/e2e-android.sh`, and empty
        // otherwise — a test that needs a Portal skips itself rather than
        // failing, so `connectedAndroidTest` stays runnable without one.
        //
        // These were being passed on the command line and read by nobody: the
        // script has always set -PnowhereE2ePortal, and until this existed the
        // value went into the build and stopped there.
        // Only when actually supplied. Baking an empty value in would override
        // the same argument passed at run time as
        // `-Pandroid.testInstrumentationRunnerArguments.<name>=…`, which is the
        // form a script uses when it varies a setting between runs — that form
        // reaches `am instrument` without changing the APK, so it does not
        // trigger a reinstall, and a reinstall clears the VPN consent grant.
        listOf("nowhereE2ePortal", "nowhereE2eKey", "nowhereE2eTarget", "nowhereE2eOrigin", "nowhereE2eMux")
            .forEach { name ->
                (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }?.let {
                    testInstrumentationRunnerArguments[name] = it
                }
            }

        ndk {
            // Two ABIs, for two different reasons: arm64-v8a is what physical
            // devices and local emulators need, x86_64 is what AVDs on x86 CI
            // runners need. The donor project ships only the former.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Tags without the "v", which is the form both libraries report at
        // runtime. QuicStackVersionTest compares these with what the linked
        // archives say about themselves.
        buildConfigField("String", "NGTCP2_VERSION", "\"${quicPin("NGTCP2_TAG").removePrefix("v")}\"")
        buildConfigField("String", "AWSLC_VERSION", "\"${quicPin("AWSLC_TAG").removePrefix("v")}\"")

        externalNativeBuild {
            cmake {
                // lwIP is built NO_SYS: no threads, no locks, one caller.
                arguments += "-DANDROID_STL=c++_static"
            }
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

    // The lwIP TCP/IP stack, inherited from Anywhere-Android at e9a9274.
    //
    // It is the peer stack for the device's own kernel: the VpnService TUN
    // hands over IP packets, lwIP terminates them, and what comes out the far
    // side is a stream with a destination — which is the shape the Nowhere
    // flow layer takes as input.
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
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
                    // The DNS layer joins the protocol layer's gate rather than
                    // the app's, because it is the same kind of code: a parser
                    // of bytes an untrusted network chose, whose failures are
                    // silent. It happens not to be Nowhere's own wire format.
                    "eu.nodepass.somewhere.dns.*",
                    // Which applications may be routed is policy, and policy
                    // that is wrong is wrong silently — the same reason the
                    // DNS layer is here.
                    "eu.nodepass.somewhere.apps.*",
                    // A wrong routing decision is wrong silently and in the
                    // worst direction: traffic leaves the device somewhere the
                    // user did not ask it to.
                    "eu.nodepass.somewhere.routing.*",
                )
            }
            excludes {
                classes(
                    // The one class in `apps` that a JVM test cannot reach. It
                    // is an adapter over `PackageManager` and holds no rule of
                    // its own: everything it could get wrong lives in
                    // AppInventory, which is inside the gate. Excluded by name
                    // so that the exemption is visible rather than achieved by
                    // leaving the whole package out.
                    "eu.nodepass.somewhere.apps.PackageManagerApps",
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

/**
 * The NDK this build is pinned to, found the same way the toolchain is: the SDK
 * location, then `ndkVersion`. AGP 9 no longer exposes `android.ndkDirectory`,
 * and guessing the newest installed NDK would defeat the point of pinning one.
 */
fun pinnedNdkDirectory(): File {
    val sdk =
        localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
            ?: error("no SDK location: set sdk.dir in local.properties or ANDROID_SDK_ROOT")
    val ndk = File(sdk, "ndk/${android.ndkVersion}")
    if (!ndk.isDirectory) error("no NDK ${android.ndkVersion} at $ndk")
    return ndk
}

// ── What the shipped native libraries export, and how they are laid out ─────
// Added when the QUIC stack landed, after a measurement rather than a worry.
// The first build that linked aws-lc exported 1,700 symbols, 1,684 of them the
// crypto library's internals. This process already runs a second BoringSSL —
// Conscrypt, which is what the L1 TLS path uses for its exporter and for ALPN —
// and two of them exporting the same names globally is how a symbol resolves
// into the wrong one, inside TLS, silently, far from its cause.
//
// The other two checks are things nothing here has ever had to care about
// before, because this repository had never shipped a third-party .so:
//
//   * 16 KB page alignment. Android 15 and later run on devices with 16 KB
//     pages, where a 4 KB-aligned library will not load. The failure is an app
//     that installs and refuses to start, on hardware this project currently
//     cannot test on at all.
//   * No libc++_shared.so. The STL is linked statically on purpose; a shared
//     one would have to be packaged, and its absence from the APK is the kind
//     of thing discovered at runtime.
//
// It reads the APK rather than a build intermediate, so it checks what ships.

abstract class NativeLibraryChecksTask : DefaultTask() {
    @get:InputFile
    abstract val apk: RegularFileProperty

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:Input
    abstract val requiredAbis: ListProperty<String>

    private fun tool(name: String): File {
        val prebuilt = File(ndkDirectory.get(), "toolchains/llvm/prebuilt")
        val host =
            prebuilt.listFiles()?.firstOrNull { it.isDirectory }
                ?: throw GradleException("no LLVM toolchain under $prebuilt")
        return File(host, "bin/$name").also {
            if (!it.canExecute()) throw GradleException("no $name at $it")
        }
    }

    private fun run(
        tool: File,
        vararg args: String,
    ): String {
        val process = ProcessBuilder(listOf(tool.absolutePath) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("${tool.name} failed:\n$output")
        return output
    }

    @TaskAction
    fun check() {
        val nm = tool("llvm-nm")
        val readelf = tool("llvm-readelf")
        val extracted = temporaryDir.resolve("lib").also { it.deleteRecursively() }
        val found = mutableMapOf<String, File>()

        ZipFile(apk.get().asFile).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val abi = entry.name.removePrefix("lib/").substringBefore('/')
                    val out = extracted.resolve(entry.name.removePrefix("lib/"))
                    out.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    found[abi] = out
                }
        }

        val missing = requiredAbis.get() - found.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "the APK carries no native library for ${missing.joinToString()}. " +
                    "Both ABIs ship for different reasons: physical devices and local emulators " +
                    "need arm64-v8a, x86 CI runners need x86_64.",
            )
        }

        val problems = mutableListOf<String>()
        found.toSortedMap().forEach { (abi, library) ->
            val exported =
                run(nm, "--dynamic", "--defined-only", library.absolutePath)
                    .lineSequence()
                    .mapNotNull { it.trim().substringAfterLast(' ').takeIf(String::isNotBlank) }
                    .toList()
            val strays = exported.filterNot { it.startsWith("Java_") }
            if (strays.isNotEmpty()) {
                problems +=
                    "$abi exports ${strays.size} symbol(s) that are not JNI entry points, " +
                    "starting with ${strays.take(5).joinToString()}. " +
                    "See app/src/main/jni/exports.map."
            }

            val headers = run(readelf, "-l", library.absolutePath)
            val alignments =
                headers
                    .lineSequence()
                    .filter { it.trimStart().startsWith("LOAD") }
                    .mapNotNull { it.trim().split(Regex("\\s+")).lastOrNull() }
                    .toSet()
            val tooSmall = alignments.filter { it.removePrefix("0x").toLong(16) < 0x4000L }
            if (tooSmall.isNotEmpty()) {
                problems +=
                    "$abi has LOAD segments aligned to ${tooSmall.joinToString()}, below the " +
                    "16 KB (0x4000) an Android 15+ device with 16 KB pages requires. Such a " +
                    "library does not load, so the app installs and will not start."
            }

            val needed =
                run(readelf, "-d", library.absolutePath)
                    .lineSequence()
                    .filter { it.contains("NEEDED") }
                    .map { it.substringAfterLast('[').substringBefore(']') }
                    .toList()
            if (needed.any { it.contains("c++_shared") }) {
                problems +=
                    "$abi links libc++_shared.so, but the STL is linked statically on purpose " +
                    "and nothing packages the shared one into the APK."
            }

            logger.lifecycle(
                "$abi: ${library.length()} bytes, ${exported.size} exported symbols, " +
                    "LOAD alignment ${alignments.joinToString()}, needs ${needed.joinToString()}",
            )
        }

        if (problems.isNotEmpty()) {
            throw GradleException("native library checks failed:\n" + problems.joinToString("\n") { "  $it" })
        }
    }
}

val nativeLibraryChecks =
    tasks.register<NativeLibraryChecksTask>("checkNativeLibraries") {
        group = "verification"
        description = "What the shipped .so files export, how they are aligned, and what they need."
        dependsOn("assembleDebug")
        apk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        ndkDirectory.set(pinnedNdkDirectory().absolutePath)
        requiredAbis.set(listOf("arm64-v8a", "x86_64"))
    }

tasks.named("check") { dependsOn(nativeLibraryChecks) }

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
