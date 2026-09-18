plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
}

kotlin {
    android {
        namespace = "com.nrknithin.fintrack.shared"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    // Android only. The fork ships no iOS app, so the Kotlin/Native targets and the
    // framework binary they produced are gone. This module stays multiplatform because
    // :app depends on it and its sources live in commonMain — the plugin, not the targets,
    // is what that needs.

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.pdfbox.android)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
}

ksp {
    arg("room.generateKotlin", "true")
}
