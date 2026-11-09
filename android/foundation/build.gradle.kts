plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lavasec.app.foundation"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.icu4j)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
