import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pennywiseai.tracker"
    compileSdk = 37
    
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.pennywiseai.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 103
        versionName = "2.19.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Load RSA public key from local.properties
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            val localProperties = Properties()
            localProperties.load(localPropertiesFile.inputStream())

            val rsaPublicKey = localProperties.getProperty("RSA_PUBLIC_KEY", "")
            buildConfigField("String", "RSA_PUBLIC_KEY", "\"$rsaPublicKey\"")
        } else {
            // Fallback empty key for CI/CD builds
            buildConfigField("String", "RSA_PUBLIC_KEY", "\"\"")
        }
    }

    signingConfigs {
        // Only create signing config for non-F-Droid builds
        if (!gradle.startParameter.taskNames.any { it.contains("fdroid", ignoreCase = true) }) {
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                create("release") {
                    val localProperties = Properties()
                    localProperties.load(localPropertiesFile.inputStream())
                    
                    val keystorePath = localProperties.getProperty("RELEASE_STORE_FILE", "")
                    if (keystorePath.isNotEmpty()) {
                        storeFile = file(keystorePath)
                        storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
                        keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
                        keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
                    }
                }
            }
        }
    }
    
    flavorDimensions += "version"
    productFlavors {
        create("fdroid") {
            dimension = "version"
            // F-Droid builds will use their own signing
            // Only include ARM architectures for F-Droid (no x86 emulator support)
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
            // Type-safe flavor check for code that adapts to F-Droid (everything
            // unlocked, tip-jar instead of Pro). Beats matching BuildConfig.FLAVOR
            // against the "fdroid" string literal, which a typo would silently break.
            buildConfigField("boolean", "IS_FDROID_BUILD", "true")
        }
        create("standard") {
            dimension = "version"
            isDefault = true
            // Standard flavor includes all architectures (including x86 for emulators)
            buildConfigField("boolean", "IS_FDROID_BUILD", "false")
        }
        // Fin's own flavor (spec 05 §5.2): fdroid's behaviour — everything unlocked, no Play
        // billing — plus the optional cloud pieces of spec 08. This is the build that ships.
        create("personal") {
            dimension = "version"
            // Owner's choice (2026-09-18). Spec 05 §5.2 wrote `in.nrk.fin`; recorded in
            // docs/FORK-CHANGES.md. Package names *in code* stay com.pennywiseai.tracker so the
            // upstream diff stays small — only the installed identity changes.
            //
            // This is the identity Android installs under, and it is effectively permanent: a
            // later change is a different app, so the old one has to be uninstalled and its data
            // is lost. It also has to match the package registered in Firebase exactly.
            applicationId = "com.nrknithin.fintrack"
            // Every Pro gate resolves to entitled, exactly as on fdroid (spec 02 §2.9).
            buildConfigField("boolean", "IS_FDROID_BUILD", "true")
            // Spec 08's cloud code compiles only here; the fdroid flavor stays the control
            // build that proves the app is complete with every cloud feature off (C8).
            buildConfigField("boolean", "HAS_CLOUD", "true")
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    // Product flavors in one dimension do NOT share source sets, so `personal` cannot see
    // app/src/fdroid/. The four classes both need — FdroidBillingGateway, BillingModule,
    // InAppReviewManager, InAppUpdateManager — live in app/src/unlocked/ and are wired into
    // both here. Without this the personal flavor does not compile at all: app/src/main
    // references EntitlementSource and PurchaseGateway, which only a flavor supplies.
    // Both `java` and `kotlin`: AGP's java.srcDir registers the directory for the Java
    // compiler, but the Kotlin plugin keeps its own source-set list and will not compile
    // .kt files it has not been told about — the symptom is "Unresolved reference" from
    // app/src/main for classes that plainly exist on disk.
    sourceSets {
        getByName("fdroid") {
            java.srcDir("src/unlocked/java")
            kotlin.srcDir("src/unlocked/java")
        }
        getByName("personal") {
            java.srcDir("src/unlocked/java")
            kotlin.srcDir("src/unlocked/java")
        }
    }

    splits {
        abi {
            // Disable splits for F-Droid builds and bundle builds
            //noinspection WrongGradleMethod
            val runTasks = gradle.startParameter.taskNames.map { it.lowercase() }
            //noinspection WrongGradleMethod
            val isBundleBuild = runTasks.any { it.contains("bundle") }   // e.g., :app:bundleRelease
            //noinspection WrongGradleMethod
            val isFdroidBuild = runTasks.any { it.contains("fdroid") }

            isEnable = !(isBundleBuild || isFdroidBuild)

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }


    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Only apply signing config to standard flavor
            for (flavor in productFlavors) {
                if (flavor.name == "standard") {
                    // Check if release signing config exists
                    val releaseSigningConfig = signingConfigs.findByName("release")
                    // Only use release signing if keystore is configured
                    if (releaseSigningConfig != null && releaseSigningConfig.storeFile != null) {
                        signingConfig = releaseSigningConfig
                    }
                }
            }
            
            // Include debug symbols for native crashes
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
            )
            // JavaMail (com.sun.mail:android-mail + android-activation) finds its IMAP
            // provider and its MIME type handlers by reading these resources at runtime.
            // android-mail and android-activation both ship some of them, so the merger
            // sees duplicates — pickFirsts keeps one copy. They must NOT be excluded: an
            // APK without javamail.providers throws "no provider for imaps" on the first
            // sync, and only at runtime.
            pickFirsts += setOf(
                "META-INF/javamail.providers",
                "META-INF/javamail.default.providers",
                "META-INF/javamail.address.map",
                "META-INF/javamail.default.address.map",
                "META-INF/javamail.charset.map",
                "META-INF/mailcap",
                "META-INF/mailcap.default",
                "META-INF/mimetypes.default",
            )
        }
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Allow JVM unit tests to exercise code that touches android.util.Log
            // and other Android framework stubs without Robolectric.
            isReturnDefaultValues = true

            // Robolectric reads the merged manifest and the packaged resources through the
            // `com.android.tools.test_config.properties` file AGP writes only when this is on.
            // Without it a Robolectric test cannot start at all, and the Room suites under
            // `data/database` are the first tests here that need a real Android runtime.
            isIncludeAndroidResources = true

            all {
                // Gradle's default test heap is 512 MB, and the suite outgrew it: each
                // Robolectric sandbox loads an instrumented copy of the Android framework,
                // and the Room suites add a generated `PennyWiseDatabase_Impl` and an
                // in-memory SQLite per test. Past that line the worker JVM dies *while*
                // writing its results, so Gradle fails the task with
                // `NoSuchFileException: …/binary/in-progress-results-generic.bin` or a bare
                // `EOFException` — messages that name a missing file rather than a missing
                // megabyte, and cost an afternoon to read correctly. The build daemon is
                // already given 4 GB in `gradle.properties`; this is the test worker, which
                // is a different JVM and inherits none of it.
                it.maxHeapSize = "2g"
            }
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Configure Room schema export
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

// Copy changelog from fastlane to generated assets for What's New dialog
val generatedAssetsDir = layout.buildDirectory.dir("generated/assets/changelog")

tasks.register<Copy>("copyChangelog") {
    val versionCode = android.defaultConfig.versionCode
    val changelogDir = rootProject.file("fastlane/metadata/android/en-US/changelogs")
    val changelogFile = file("$changelogDir/$versionCode.txt")
    val defaultFile = file("$changelogDir/default.txt")

    from(if (changelogFile.exists()) changelogFile else defaultFile)
    into(generatedAssetsDir)
    rename { "whats_new.txt" }
}

android.sourceSets["main"].assets.directories.add(generatedAssetsDir.get().asFile.path)

tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }.configureEach {
    dependsOn("copyChangelog")
}

// Lint tasks also need to depend on copyChangelog since they analyze generated assets
tasks.matching { it.name.contains("lint") || it.name.contains("Lint") }.configureEach {
    dependsOn("copyChangelog")
}

dependencies {
    // Local modules
    implementation(project(":parser-core"))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Material Icons Extended
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Color Picker for Compose
    implementation(libs.colorpicker.compose)
    
    // Splash Screen API
    implementation(libs.androidx.core.splashscreen)
    
    // Lifecycle and ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Ktor for HTTP requests
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    // Gson for backup/restore
    implementation(libs.gson)
    
    // DataStore
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // Biometric Authentication
    implementation(libs.androidx.biometric)

    // --- Fin: the email channel (spec 11, spec 04 §4.1) ---------------------------------
    // JavaMail's Android port, for IMAPS. Its companion android-activation supplies the
    // DataHandler / MIME registry; without it multipart bodies fail at runtime, not at
    // compile time. :parser-core never sees either of these — it stays Android-free and
    // is handed a plain String (spec 02 §2.1).
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    // HTML → text for email bodies, with Safelist.none() semantics (spec 04 §4.1).
    implementation(libs.jsoup)

    // MasterKey + EncryptedSharedPreferences for the Gmail app password. This is the only
    // place the one secret in the app is stored (spec 04 §4.1, constraint C1).
    implementation(libs.androidx.security.crypto)

    // Timber, so the release build logs through one tree that drops VERBOSE/DEBUG and
    // redacts amounts and last-4s from everything above (spec 04 §4.6, AC-11-04). The
    // tree itself is `RedactingReleaseTree` in util/LogRedactor.kt; PennyWiseApplication
    // plants it. Nothing here depends on Android, so the redactor is a plain JVM test.
    implementation(libs.timber)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    
    // Hilt WorkManager integration
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Glance Widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // LiteRT-LM >= 0.14 calls SendChannel.close$default as an interface static; only
    // coroutines >= 1.11.0 provides it (see the litertlm note in libs.versions.toml, #599).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // LiteRT-LM for on-device LLM inference
    implementation(libs.litertlm.android)
    
    // Google Play In-App Updates (only for standard flavor)
    "standardImplementation"(libs.app.update)
    "standardImplementation"(libs.app.update.ktx)
    
    // Google Play In-App Reviews (only for standard flavor)
    "standardImplementation"(libs.review)
    "standardImplementation"(libs.review.ktx)
    
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)

    // --- Fin: JVM unit-test support for the email channel -------------------------------
    // runTest / TestDispatcher. Pinned to the same 1.11.0 as coroutines-core above; a
    // mismatched kotlinx-coroutines-test resolves the whole coroutines group back down.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // TestListenableWorkerBuilder for EmailSyncWorker. Already used by androidTest; the
    // email worker's algorithm (windowing, batching, resume) is exercised as a JVM test.
    testImplementation(libs.androidx.work.testing)
    // JVM MockK, for stubbing the DAOs the ingestor talks to. mockk-android below is the
    // instrumented build of the same library and cannot be used from src/test.
    testImplementation(libs.mockk)

    // Robolectric, so a real PennyWiseDatabase can be opened from a JVM unit test. Until this
    // landed no SQL in this repo had ever run: every DAO was verified only by Room's
    // compile-time check, and every DAO "test" asserted against a hand-written fake, which can
    // agree with the Kotlin signature while the SQL underneath is wrong. Robolectric brings its
    // own android-all jar (fetched once from Maven Central) and its own SQLite, so none of this
    // needs a device. See app/src/test/resources/robolectric.properties for the pinned API level.
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    
    // Coil for image loading
    implementation(libs.coil.compose)

    // Haze blur effects
    implementation(libs.haze)

    // Compose Charts
    implementation(libs.compose.charts)

    // Markdown support
    implementation(libs.markdown)
    
    // OpenCSV for CSV export
    implementation(libs.opencsv)

    // PDFBox Android for PDF statement parsing
    implementation(libs.pdfbox.android)

    // Google Play Billing — STANDARD FLAVOR ONLY. F-Droid forbids the
    // proprietary library; its build keeps Pro features unlocked via the
    // FdroidBillingGateway stub and does not need the dep.
    "standardImplementation"(libs.billing.ktx)

    testImplementation(kotlin("test"))
}
