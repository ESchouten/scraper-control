// The Android app: an Activity around the Compose UI (:composeApp), the core's JNI library
// built by the NDK from the robot driver, and the home-screen widgets.
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "nl.eschouten.scraper"
  compileSdk { version = release(37) { minorApiLevel = 0 } }
  defaultConfig {
    applicationId = "nl.eschouten.scraper"
    minSdk = 24
    targetSdk = 36
    versionCode = (providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }
      .standardOutput.asText.get().trim().toIntOrNull() ?: 1) +
      (providers.gradleProperty("scraper.buildBase").orNull?.toIntOrNull() ?: 0)
    versionName = providers.gradleProperty("scraper.version").get()
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    externalNativeBuild { cmake { arguments += "-DSCRAPER_DRIVER=${providers.gradleProperty("scraper.driver").get()}" } }
  }
  externalNativeBuild {
    cmake { path = file("../../core/CMakeLists.txt") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  // Release builds (the release workflow): signed with the project's own key, so every
  // version installs over the previous one (Obtainium, a downloaded APK). The keystore comes
  // from the environment: SCRAPER_KEYSTORE (path) and SCRAPER_KEYSTORE_PASSWORD (the store's and
  // the key's). Without them a release build is unsigned.
  val keystore = System.getenv("SCRAPER_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
  if (keystore != null) {
    signingConfigs {
      create("release") {
        storeFile = keystore
        storePassword = System.getenv("SCRAPER_KEYSTORE_PASSWORD")
        keyAlias = "scraper-control"
        keyPassword = System.getenv("SCRAPER_KEYSTORE_PASSWORD")
      }
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      if (keystore != null) signingConfig = signingConfigs.getByName("release")
    }
  }
}

dependencies {
  implementation(project(":composeApp"))
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
