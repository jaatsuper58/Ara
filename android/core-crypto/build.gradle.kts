plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ara.core.crypto"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Crypto tests are pure JVM (BouncyCastle + JDK crypto), so they run on CI without a device.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":core-common"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // BouncyCastle supplies X25519/Ed25519 primitives. PRODUCTION NOTE: the message
    // crypto layer is designed so the engine can be swapped to the audited
    // org.signal:libsignal-android behind the same MessageCrypto interface —
    // see docs/security.md ("Use libsignal in production").
    implementation(libs.bouncycastle)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
