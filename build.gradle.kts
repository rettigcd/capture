// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9+ has built-in Kotlin support and forbids also applying org.jetbrains.kotlin.android
    // (see https://kotl.in/gradle/agp-built-in-kotlin) - only the Compose compiler plugin, which
    // AGP's built-in support does not include, still needs to be applied explicitly.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
}
