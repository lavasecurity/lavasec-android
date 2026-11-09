plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lavasec.app.testsupport"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.junit)
    api(libs.androidx.test.core)
    api(libs.robolectric)
}
