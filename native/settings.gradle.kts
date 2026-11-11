// Scraper Control, native apps: shared Kotlin logic + Compose (Android, desktop) + SwiftUI (iOS).
// Plan: https://claude.ai/artifact/9SX5eFsjHo3ntXaCY1RoAh
// Android parts are only configured when an Android SDK is present (ANDROID_HOME), so the
// shared module and the desktop app also build on a machine without one.
rootProject.name = "scraper-control"

val hasAndroid = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { v ->
  System.getenv(v)?.let { File(it).isDirectory } == true
}

dependencyResolutionManagement {
  repositories {
    // Android, and Compose desktop too (its androidx artifacts live only on Google Maven).
    google { content { includeGroupByRegex("androidx\\..*|com\\.android.*|com\\.google\\.android.*|com\\.google\\.testing.*") } }
    mavenCentral()
  }
}

include(":shared", ":composeApp")
if (hasAndroid) include(":androidApp")
