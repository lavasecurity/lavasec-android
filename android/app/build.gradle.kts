import java.io.File
import java.util.Properties
import java.util.Base64
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

plugins {
    id("com.facebook.react")
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

react {
    root.set(rootProject.layout.projectDirectory.dir("react-native"))
    reactNativeDir.set(root.dir("node_modules/react-native"))
    codegenDir.set(root.dir("node_modules/@react-native/codegen"))
    // The existing audited preBuild task owns offline bundles in both variants.
    debuggableVariants.set(listOf("playDebug", "playRelease", "fossDebug", "fossRelease"))
    autolinkLibrariesWithApp()
}
extra["REACT_NATIVE_NODE_MODULES_DIR"] = rootProject.file("react-native/node_modules/react-native").absolutePath

// Public connection coordinates; private service-role credentials are never application inputs.
val publicServiceDefaults = Properties().apply {
    rootProject.file("service-config.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun publicServiceValue(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name)).orElse(publicServiceDefaults.getProperty(name, "")).get()
fun buildConfigString(value: String): String = groovy.json.JsonOutput.toJson(value)
val supabasePublishableKey = publicServiceValue("LAVA_SUPABASE_PUBLISHABLE_KEY")
val publicSupabaseKeyValid = supabasePublishableKey.isBlank() || supabasePublishableKey.startsWith("sb_publishable_") || runCatching {
    val segments = supabasePublishableKey.split('.')
    require(segments.size == 3)
    val claims = groovy.json.JsonSlurper().parseText(String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)) as? Map<*, *>
    claims?.get("role") == "anon"
}.getOrDefault(false)


val releaseVersion = Properties().apply {
    rootProject.file("../release/android-version.properties").inputStream().use { load(it) }
}
val lavasecVersionName = providers.gradleProperty("lavasecVersionName").orElse(releaseVersion.getProperty("versionName"))
val lavasecVersionCode = providers.gradleProperty("lavasecVersionCode").map(String::toInt).orElse(releaseVersion.getProperty("versionCode").toInt())
val playCloudProjectNumber = publicServiceValue("LAVA_PLAY_CLOUD_PROJECT_NUMBER").let { value ->
    value.toLongOrNull()?.takeIf { it > 0 } ?: 0L
}
val appleBrowserOAuthEnabled = publicServiceValue("LAVA_APPLE_BROWSER_OAUTH_ENABLED").let { value ->
    when (value) {
        "", "false" -> false
        "true" -> true
        else -> false
    }
}

android {
    namespace = "com.lavasec.app"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.lavasec.app"
        minSdk = 28
        targetSdk = 36
        versionCode = lavasecVersionCode.get().also {
            require(it in 1..2_100_000_000) { "lavasecVersionCode must be in Android's supported range" }
        }
        versionName = lavasecVersionName.get().also {
            require(it.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))) { "lavasecVersionName must be a three-component marketing version" }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "IS_FOSS", "false")
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "0L")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildConfigString(""))
        buildConfigField("boolean", "APPLE_BROWSER_OAUTH_ENABLED", "false")
        buildConfigField("String", "BACKEND_URL", buildConfigString(""))
        buildConfigField("String", "SUPABASE_URL", buildConfigString(""))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(""))
        buildConfigField("String", "BACKUP_PASSKEY_RP_ORIGIN", buildConfigString(""))
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            manifestPlaceholders["lavaDeepLinkScheme"] = "lavasecurity"
            buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${playCloudProjectNumber}L")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildConfigString(publicServiceValue("LAVA_GOOGLE_SERVER_CLIENT_ID")))
            buildConfigField("boolean", "APPLE_BROWSER_OAUTH_ENABLED", appleBrowserOAuthEnabled.toString())
            buildConfigField("String", "BACKEND_URL", buildConfigString(publicServiceValue("LAVA_BACKEND_URL")))
            buildConfigField("String", "SUPABASE_URL", buildConfigString(publicServiceValue("LAVA_SUPABASE_URL")))
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", buildConfigString(supabasePublishableKey))
            // Relying-party origin for the passkey-PRF backup ceremony. Blank disables the method
            // (fail-closed); an operator sets LAVA_BACKUP_PASSKEY_RP_ORIGIN and serves the matching
            // /.well-known/assetlinks.json before it becomes selectable.
            buildConfigField("String", "BACKUP_PASSKEY_RP_ORIGIN", buildConfigString(publicServiceValue("LAVA_BACKUP_PASSKEY_RP_ORIGIN")))
        }
        create("foss") {
            dimension = "distribution"
            applicationIdSuffix = ".foss"
            buildConfigField("boolean", "IS_FOSS", "true")
            manifestPlaceholders["lavaDeepLinkScheme"] = "lavasecurity-foss"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // The passkey-PRF relying-party origin is a release-only association: the served
            // Digital Asset Links cover the release package com.lavasec.app and release signer,
            // not the `.debug` package/CI debug signer. Blank the debug value so a debug build
            // fails closed instead of offering a passkey ceremony that cannot associate.
            buildConfigField("String", "BACKUP_PASSKEY_RP_ORIGIN", buildConfigString(""))
            // Local emulator runs can use the public CI debug signer without replacing the
            // developer's normal ~/.android keystore. This property never signs release builds.
            providers.gradleProperty("lavaDebugKeystore").orNull?.let { path ->
                signingConfig = signingConfigs.getByName("debug").apply {
                    storeFile = file(path)
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
        release {
            // The candidate is R8-minified and resource-shrunk: a debug
            // assembly alone would miss stripped registration and shrunk-away
            // assets. ci/verify_android_bundle.py re-proves the packaged
            // registration against the release AAB's DEX, not only the debug
            // build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Unit tests have their own JVM; the Gradle daemon's heap does not size it.
            // Retain the full Plus round trips and 64 MiB refusal fixture unchanged.
            it.maxHeapSize = "1g"
            // Preserve the original assertion and coroutine stack in Linux CI,
            // including when a later job timeout prevents report upload.
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Same owned-library deduplication as RN's NdkConfiguratorUtils: AAR JNI and
        // Prefab expose these libraries twice; prefer the application's NDK STL.
        jniLibs.pickFirsts += listOf(
            "**/libfbjni.so", "**/libreactnative.so", "**/libjsi.so", "**/libc++_shared.so",
        )
    }
}

dependencies {
    implementation(libs.icu4j)
    "playImplementation"(libs.google.play.billing)
    "playImplementation"(libs.google.play.integrity)
    "playImplementation"(libs.androidx.credentials)
    "playImplementation"(libs.androidx.credentials.play.services)
    "playImplementation"(libs.google.fido)
    "playImplementation"(libs.google.id)
    implementation(project(":foundation"))
    implementation(project(":product"))
    implementation(project(":platform-contracts"))
    implementation(project(":presentation"))
    implementation(project(":design-system"))
    implementation(project(":vpn"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.window)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.react.android)
    implementation(libs.hermes.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// ---- React Native JS bundling ------------------------------------------------
//
// The official React Native plugin owns autolinking and native code generation.
// This task retains the existing source/digest-verified offline JS asset pipeline
// for both debug and release. Settings installs npm dependencies before discovering
// native projects, so preBuild must never replace their sources during compilation.
// A clean checkout requires Node. Once dependencies and a verified bundle exist,
// the bundle task can retain exactly matching bytes if Node is unavailable.
val reactNativePackageDir = layout.projectDirectory.dir("../react-native")
val reactNativeBundleAsset =
    layout.projectDirectory.file("src/main/assets/index.android.bundle")
val reactNativeSources = fileTree(reactNativePackageDir) {
    include(
        "index.js",
        "App.tsx",
        "CanonicalApp.tsx",
        "react-native.config.js",
        "app.json",
        "babel.config.js",
        "metro.config.js",
        "package.json",
        "package-lock.json",
        "tsconfig.json",
        // The pinned dependency repair changes Metro input bytes without a lock
        // change. Its recipe belongs in both Gradle inputs and the bundle stamp.
        "scripts/apply-core-removal-notification-fix.mjs",
        // The production entry resolves both the Android `src/**` tree and the
        // synced canonical tree. Both must be bundle inputs, or the Node-less
        // accept path could reuse a stale bundle after a canonical pin bump
        // that touched nothing under `src/**` (F04 boundary note).
        "src/**",
        "canonical/**",
        "platform/**",
    )
    exclude("node_modules/**", "build/**")
}

@DisableCachingByDefault(because = "Shells out to the npm-based React Native bundler")
abstract class BundleReactNativeJsTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Internal
    abstract val reactNativeDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javascriptSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val bundleOutput: RegularFileProperty

    /** Records which JS sources + lockfile produced the current bundle. */
    @get:OutputFile
    abstract val bundleSourceHash: RegularFileProperty

    @TaskAction
    fun bundle() {
        val root = reactNativeDirectory.get().asFile
        val output = bundleOutput.get().asFile
        val hashFile = bundleSourceHash.get().asFile
        val currentSourceHash = sourceHash()

        if (!nodeIsAvailable()) {
            // Only keep a prebuilt bundle when it matches the exact sources on
            // disk now AND its bytes still match the digest recorded when it
            // was produced; a truncated/replaced asset must not be packaged.
            val stampLines = hashFile.takeIf { it.isFile }?.readLines()
            val storedSourceHash = stampLines?.getOrNull(0)?.trim()
            val storedBundleDigest = stampLines?.getOrNull(1)?.trim()
            val bundleDigestMatches =
                output.isFile && storedBundleDigest == sha256(output)
            if (bundleDigestMatches && storedSourceHash == currentSourceHash) {
                logger.lifecycle(
                    "Node.js unavailable; keeping the existing React Native bundle at " +
                        "${output.path} (matches current JS sources and recorded digest).",
                )
                return
            }
            throw GradleException(
                "Node.js is required to bundle the React Native entry point: the existing bundle " +
                    "at ${output.path} is missing, does not match the current JS sources " +
                    "(package-lock.json included), or its bytes do not match the recorded digest. " +
                    "Install Node matching React Native 0.87.1 " +
                    "(^22.13.0 || ^24.3.0 || >= 26.0.0), or rebuild on a machine with Node.",
            )
        }

        val lockfile = File(root, "package-lock.json")
        if (!lockfile.isFile) {
            throw GradleException(
                "Missing ${lockfile.path}; cannot install the locked React Native JS graph.",
            )
        }
        // A lockfile-keyed stamp means a changed lock can never re-use stale
        // node_modules: Metro always bundles against the locked dependency set.
        val lockHash = sha256(lockfile)
        val lockStamp = File(root, "node_modules/.lavasec-lock-hash")
        val installedHash = lockStamp.takeIf { it.isFile }?.readText()?.trim()
        val installRequired =
            !File(root, "node_modules").isDirectory || installedHash != lockHash
        if (installRequired) {
            throw GradleException("React Native dependencies changed after settings discovery. Restart Gradle to install the locked graph before autolinking.")
        }

        execOperations.exec {
            workingDir = root
            commandLine(npmCommand("run", "bundle:android"))
        }
        if (!output.isFile) {
            throw GradleException(
                "React Native bundler reported success but did not produce ${output.path}.",
            )
        }
        val bundleDigest = sha256(output)

        // Record stamps only after a successful bundle: a build that installs
        // but fails to bundle must not leave a stamp that suppresses the next
        // install, and the digest pins the exact bytes the Node-less path may
        // reuse. Line 1 = source hash, line 2 = bundle digest.
        if (installRequired) {
            lockStamp.parentFile.mkdirs()
            lockStamp.writeText(lockHash)
        }
        hashFile.parentFile.mkdirs()
        hashFile.writeText("$currentSourceHash\n$bundleDigest\n")
    }

    private fun nodeIsAvailable(): Boolean {
        val path = System.getenv("PATH") ?: return false
        val names = nodeExecutableNames()
        return path.split(File.pathSeparator).any { entry ->
            val directory = entry.ifBlank { "." }
            names.any { name ->
                val candidate = File(directory, name)
                candidate.isFile && candidate.canExecute()
            }
        }
    }

    /** npm is a shell launcher (`npm.cmd`) on Windows, not a bare executable. */
    private fun npmCommand(vararg args: String): List<String> =
        if (isWindows()) listOf("cmd", "/c", "npm") + args else listOf("npm") + args

    /** Node is `node` on Unix but `node.exe` (or a launcher) on Windows. */
    private fun nodeExecutableNames(): List<String> =
        if (isWindows()) {
            listOf("node.exe", "node.cmd", "node.bat", "node")
        } else {
            listOf("node")
        }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Stable, path-relative hash of the JS sources plus the lockfile. */
    private fun sourceHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val base = reactNativeDirectory.get().asFile.toPath()
        javascriptSources.files.sortedBy { it.absolutePath }.forEach { file ->
            digest.update(base.relativize(file.toPath()).toString().toByteArray())
            digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}

val bundleReactNativeJs = tasks.register<BundleReactNativeJsTask>("bundleReactNativeJs") {
    group = "react native"
    description = "Bundles the React Native JS entry point into app assets."
    reactNativeDirectory.set(reactNativePackageDir)
    bundleOutput.set(reactNativeBundleAsset)
    bundleSourceHash.set(layout.buildDirectory.file("react-native/bundle-source-hash.txt"))
    javascriptSources.from(reactNativeSources)
}

tasks.named("preBuild") {
    dependsOn(bundleReactNativeJs)
}

// Defense in depth: the assets packaged into the debug APK and the release AAB
// must be merged after the bundle is written, regardless of how `preBuild` is
// wired by the AGP version in use.
tasks.matching { it.name.matches(Regex("merge(Play|Foss)(Debug|Release)Assets")) }
    .configureEach { dependsOn(bundleReactNativeJs) }

// Validate only the Play lane. Irrelevant/missing account configuration never prevents FOSS builds.
val validatePlayServiceConfiguration = tasks.register("validatePlayServiceConfiguration") {
    doLast {
        require(publicSupabaseKeyValid) { "LAVA_SUPABASE_PUBLISHABLE_KEY must be a public project key; secret credentials must never be packaged" }
        val project = publicServiceValue("LAVA_PLAY_CLOUD_PROJECT_NUMBER")
        require(project.isBlank() || project.toLongOrNull()?.let { it > 0 } == true) {
            "LAVA_PLAY_CLOUD_PROJECT_NUMBER must be the Play-linked Google Cloud project number"
        }
        require(publicServiceValue("LAVA_APPLE_BROWSER_OAUTH_ENABLED") in listOf("", "true", "false")) {
            "LAVA_APPLE_BROWSER_OAUTH_ENABLED must be true or false"
        }
    }
}
tasks.matching { it.name.matches(Regex("prePlay(Debug|Release)Build")) }
    .configureEach { dependsOn(validatePlayServiceConfiguration) }
