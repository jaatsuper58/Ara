plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ara.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ara.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Phase 11: signing wired from local.properties / CI secrets — the keystore
    // and its passwords are NEVER committed (see docs/release.md).
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ARA_KEYSTORE_PATH")
                ?: (project.rootProject.file("../local.properties").takeIf { it.exists() }?.let { file ->
                    val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
                    props.getProperty("ara.keystore.path")
                })
            val keystorePass = System.getenv("ARA_KEYSTORE_PASSWORD")
                ?: (project.rootProject.file("../local.properties").takeIf { it.exists() }?.let { file ->
                    val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
                    props.getProperty("ara.keystore.password")
                })
            if (keystorePath != null && keystorePass != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = System.getenv("ARA_KEY_ALIAS") ?: "ara"
                keyPassword = System.getenv("ARA_KEY_PASSWORD") ?: keystorePass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-ui"))
    implementation(project(":core-crypto"))
    implementation(project(":core-database"))
    implementation(project(":core-network"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-calls"))
    implementation(project(":feature-contacts"))
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.biometric)

    // FCM: push wake-ups only (encrypted payloads never include content).
    // NOTE: to enable real push, add google-services.json + the
    // com.google.gms.google-services plugin (docs/release.md).
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
