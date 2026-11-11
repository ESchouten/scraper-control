// The Kotlin, Compose and Android Gradle plugins all come from here, so they share one
// class loader (the Kotlin plugin needs to see the Android one).
buildscript {
  val hasAndroid = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { v ->
    System.getenv(v)?.let { File(it).isDirectory } == true
  }
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
