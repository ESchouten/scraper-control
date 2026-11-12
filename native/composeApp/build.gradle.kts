// The Compose UI, one code base for Android and the desktop (Compose Multiplatform).
// iOS has its own SwiftUI app (iosApp/) on top of the same :shared module.
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

val hasAndroid = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { v ->
  System.getenv(v)?.let { File(it).isDirectory } == true
}

kotlin {
  jvm {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
  }
  sourceSets {
    commonMain.dependencies {
      api(project(":shared"))
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
      implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
      implementation(compose.uiTest)
      implementation(compose.desktop.currentOs)
    }
    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    }
  }
}

compose.desktop {
  application {
    mainClass = "nl.eschouten.scraper.app.MainKt"
    nativeDistributions {
      packageName = "Scraper Control"
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
      )
      // 1.6.120: MAJOR.MINOR from gradle.properties, the build number as the third number
      packageVersion = providers.gradleProperty("scraper.version").get() + "." +
        ((providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }
          .standardOutput.asText.get().trim().toIntOrNull() ?: 0) +
          (providers.gradleProperty("scraper.buildBase").orNull?.toIntOrNull() ?: 0))
    }
  }
}

if (hasAndroid) apply(from = "android.gradle")
