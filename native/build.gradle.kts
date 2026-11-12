// The Kotlin, Compose and Android Gradle plugins all come from here, so they share one
// class loader (the Kotlin plugin needs to see the Android one).
buildscript {
  val hasAndroid = gradle.extra["hasAndroid"] as Boolean   // settings.gradle.kts
  repositories {
    // Android, and Compose desktop too (its androidx artifacts live only on Google Maven).
    google { content { includeGroupByRegex("androidx\\..*|com\\.android.*|com\\.google\\.android.*|com\\.google\\.testing.*") } }
    mavenCentral()
    gradlePluginPortal()
  }
  dependencies {
    val kotlin = "2.4.21"
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin")
    classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlin")
    classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlin")
    classpath("org.jetbrains.compose:compose-gradle-plugin:1.12.2")
    if (hasAndroid) classpath("com.android.tools.build:gradle:9.1.0")
  }
}

// One version for every app (the M5 too, firmware/version.py): MAJOR.MINOR from
// gradle.properties + the build number (commit count + scraper.buildBase): 1.6.120.
val appBuild = (providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }
  .standardOutput.asText.get().trim().toIntOrNull() ?: 0) + providers.gradleProperty("scraper.buildBase").get().toInt()
extra["appBuild"] = appBuild
extra["appVersion"] = "${providers.gradleProperty("scraper.version").get()}.$appBuild"
// Android: the same SDK levels for the app and both libraries (*/android.gradle)
extra["androidCompileSdk"] = 37
extra["androidMinSdk"] = 24
