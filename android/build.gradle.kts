buildscript {
    dependencies {
        classpath("com.facebook.react:react-native-gradle-plugin")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["compileSdkVersion"] = 36
extra["targetSdkVersion"] = 36
extra["minSdkVersion"] = 28
extra["ndkVersion"] = "27.2.12479018"

// Shared SVG uses Node resolution from the Android root by default. Our locked
// RN package lives in react-native/, so every autolinked library needs its actual root.
extra["REACT_NATIVE_NODE_MODULES_DIR"] = file("react-native/node_modules/react-native").absolutePath
