pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-download the JDK 21 toolchain (see `kotlin { jvmToolchain(21) }` in
    // app/build.gradle.kts) on machines that don't already have one installed, instead of just
    // failing with "Toolchain download repositories have not been configured".
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Capture"
include(":app")
