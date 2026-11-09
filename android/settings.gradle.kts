pluginManagement {
    // Autolinked libraries are Gradle projects. Install their locked sources BEFORE
    // settings discovery, never concurrently with compilation in app:preBuild.
    val rn = file("react-native")
    val lock = rn.resolve("package-lock.json")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(lock.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val stamp = rn.resolve("node_modules/.lavasec-lock-hash")
    if (!stamp.isFile || stamp.readText().trim() != digest ||
        !rn.resolve("node_modules/@react-native/gradle-plugin/settings.gradle.kts").isFile) {
        val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
        providers.exec {
            workingDir(rn)
            environment("NODE_ENV", "development")
            commandLine(npm, "ci", "--include=dev", "--ignore-scripts", "--no-audit", "--no-fund")
        }.result.get().assertNormalExitValue()
        stamp.writeText(digest + "\n")
    }

    // Apply only the pinned, hash-checked upstream retirement guard before the
    // autolinked native library enters Gradle's project/input discovery.
    providers.exec {
        workingDir(rn)
        commandLine("node", "scripts/apply-native-screens-retirement-fix.mjs")
    }.result.get().assertNormalExitValue()

    // Validate and repair the shipped JS module before Metro discovers inputs,
    // including cached dependency installs. Unexpected locked sources fail closed.
    providers.exec {
        workingDir(rn)
        commandLine("node", "scripts/apply-core-removal-notification-fix.mjs")
    }.result.get().assertNormalExitValue()

    includeBuild("react-native/node_modules/@react-native/gradle-plugin")
    repositories {
        google()
        mavenCentral()
    }
}

plugins { id("com.facebook.react.settings") }

extensions.configure<com.facebook.react.ReactSettingsExtension> {
    autolinkLibrariesFromCommand(
        command = listOf("node", "node_modules/@react-native-community/cli/build/bin.js", "config"),
        workingDirectory = file("react-native"),
        lockFiles = files("react-native/package.json", "react-native/package-lock.json", "react-native/react-native.config.js"),
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lavasec-android"

include(":app")
include(":foundation")
include(":product")
include(":platform-contracts")
include(":vpn")
include(":presentation")
include(":design-system")
include(":test-support")

includeBuild("react-native/node_modules/@react-native/gradle-plugin")
