import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9+ has built-in Kotlin support, so org.jetbrains.kotlin.android is neither needed nor
    // allowed here (see https://kotl.in/gradle/agp-built-in-kotlin). The Compose compiler plugin
    // still needs to be applied explicitly - AGP's built-in Kotlin support does not include it.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    jacoco
}

android {
    namespace = "com.example.capture"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.capture"
        // Android 10 (API 29) is the first release with full scoped storage
        // (MediaStore.IS_PENDING / RELATIVE_PATH). Pinning minSdk here lets the
        // storage layer avoid a legacy WRITE_EXTERNAL_STORAGE fallback entirely.
        // See README.md "Why minSdk 29" for the full rationale.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.capture.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric's default dependency host (repo1.maven.org) is blocked on some
                // restricted networks (it was in the environment this project was generated in);
                // Maven Central's canonical hostname mirrors the same artifacts and is reachable
                // more often, so use it instead of Robolectric's default.
                it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
                it.systemProperty("robolectric.dependency.repo.id", "central")
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        disable += setOf(
            "GradleDependency",
            // res/mipmap-anydpi-v26 keeps its -v26 qualifier even though minSdk (29) already
            // implies it: an unqualified "mipmap-anydpi" folder fails resource compilation for
            // <adaptive-icon> XML with this AGP/build-tools version, so the seemingly-redundant
            // qualifier is required, not just legacy cruft.
            "ObsoleteSdkInt",
        )
    }
}

// Uses a Java toolchain (rather than the environment's ambient JDK) so builds are reproducible
// across machines and CI runners. JDK 21 was picked because AGP 9's built-in Kotlin support
// depends on a modern Kotlin Gradle Plugin baseline, and this project's full build (compile,
// unit tests, lint, assembleDebug) was verified end-to-end against a JDK 21 toolchain; the
// Gradle toolchain resolver (see the foojay-resolver-convention plugin in settings.gradle.kts)
// will download a matching JDK automatically on machines that don't already have one.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Kept false so a transient upstream deprecation warning can't fail CI on its own;
        // ktlint + Android Lint below are configured to fail the build on real issues.
        allWarningsAsErrors.set(false)
    }
}

ktlint {
    version.set("1.5.0")
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/build/**")
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val fileFilter = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "**/*_Factory.*",
        "**/*_MembersInjector.*",
        "**/*_HiltModules*.*",
        "**/Hilt_*.*",
        "**/di/**",
    )
    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    classDirectories.setFrom(files(debugTree))
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    implementation(libs.google.hilt.android)
    ksp(libs.google.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // --- Local JVM unit tests ---
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.google.hilt.compiler)

    // --- Instrumented tests ---
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.google.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
