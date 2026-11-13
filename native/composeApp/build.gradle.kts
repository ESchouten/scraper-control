// The Compose UI, one code base for Android and the desktop (Compose Multiplatform).
// iOS has its own SwiftUI app (iosApp/) on top of the same :shared module.
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
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
      // the app icon (from the iOS/Android icon), per system
      macOS {
        iconFile.set(project.file("icons/icon.icns"))
        bundleID = "nl.eschouten.scraper.desktop"
        // macOS stops an app that uses Bluetooth without saying why (BLE and classic alike)
        infoPlist {
          extraKeysRawXml = """
            <key>NSBluetoothAlwaysUsageDescription</key>
            <string>To control the robot over Bluetooth.</string>
          """.trimIndent()
        }
      }
      linux { iconFile.set(project.file("icons/icon.png")) }
      windows {
        iconFile.set(project.file("icons/icon.ico"))
        menu = true            // in the Start menu
        perUserInstall = true  // no administrator needed
        upgradeUuid = "1fe694cb-fc77-4315-bb64-e8666b0218d5"   // never changes: a newer installer replaces the older app
      }
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
      )
      packageVersion = rootProject.extra["appVersion"] as String   // 1.6.120 (build.gradle.kts)
    }
  }
}

if (findProject(":androidApp") != null) apply(from = "android.gradle")   // an Android SDK is there
