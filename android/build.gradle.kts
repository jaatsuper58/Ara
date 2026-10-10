// Top-level build file. Per-module configuration lives in each module's build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Deduplicate META-INF entries across every Android module (main + androidTest
// variants): BouncyCastle and jspecify both ship an OSGI manifest under
// META-INF/versions/9, which breaks resource merging.
subprojects {
    afterEvaluate {
        if (extensions.findByName("android") != null) {
            extensions.configure<com.android.build.gradle.BaseExtension>("android") {
                packagingOptions {
                    resources.excludes.addAll(
                        setOf(
                            "META-INF/AL2.0",
                            "META-INF/LGPL2.1",
                            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                        ),
                    )
                }
            }
        }
    }
}
