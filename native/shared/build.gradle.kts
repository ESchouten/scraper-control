// Shared logic for every app: the robot controller, settings, logbook, texts and screen models,
// and the robot driver with its C++ core (through JNI on Android/desktop, cinterop on iOS).
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlin.plugin.serialization")
}

val hasAndroid = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { v ->
  System.getenv(v)?.let { File(it).isDirectory } == true
}
// The robot driver (gradle.properties scraper.driver): drivers/<name>/src, built with the C API in core/
val driver = providers.gradleProperty("scraper.driver").get()
val driverDir = rootDir.resolve("../drivers/$driver")
val driverSrc = driverDir.resolve("src")
val apiDir = rootDir.resolve("../core")
val secretsFile = rootDir.resolve("../drivers/secrets.h") // tools/secrets.py; optional

// --- the app's version as a constant (AppVersion), the same for every app ---
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }
  .standardOutput.asText.map { (it.trim().toIntOrNull() ?: 0) + (providers.gradleProperty("scraper.buildBase").orNull?.toIntOrNull() ?: 0) }
val appVersion = commitCount.map { "${providers.gradleProperty("scraper.version").get()}.$it" }   // 1.6.120
val versionDir = layout.buildDirectory.dir("generated/version/kotlin")
val generateVersion by tasks.registering {
  val name = appVersion
  val build = commitCount
  inputs.property("version", name)
  inputs.property("build", build)
  outputs.dir(versionDir)
  doLast {
    val f = versionDir.get().asFile.resolve("nl/eschouten/scraper/AppVersion.kt")
    f.parentFile.mkdirs()
    f.writeText(
      "// Generated from gradle.properties (scraper.version) + the build number. Do not edit.\n" +
        "package nl.eschouten.scraper\n\n" +
        "object AppVersion {\n  const val NAME = \"${name.get()}\"\n  const val BUILD = ${build.get()}\n}\n",
    )
  }
}

kotlin {
  jvm {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
  }
  listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
    t.compilations.getByName("main").cinterops.create("core") {
      definitionFile.set(file("src/nativeInterop/cinterop/core.def"))
      includeDirs(apiDir)
      // libcore.a per target, built by buildCoreIos (macOS only)
      extraOpts("-libraryPath", layout.buildDirectory.dir("core-ios/${t.name}").get().asFile.path)
    }
    t.binaries.framework {
      baseName = "Shared"
      isStatic = true
      linkerOpts("-lc++")
    }
  }

  sourceSets {
    commonMain { kotlin.srcDir(generateVersion.map { versionDir.get().asFile }) }
    commonMain.dependencies {
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
      implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
      api("com.juul.kable:kable-core:0.45.0")
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    }
    // JNI code is the same for Android and desktop
    jvmMain { kotlin.srcDir("src/jniMain/kotlin") }
  }
}

// --- the core as a JNI library for this desktop (Linux .so, macOS .dylib, Windows .dll) ---
val coreJniOut = layout.buildDirectory.dir("core-jni")
val buildCoreJni by tasks.registering(Exec::class) {
  description = "Builds the robot driver with the JNI bridge (core/CMakeLists.txt) for this machine."
  val work = layout.buildDirectory.dir("core-jni-cmake").get().asFile
  inputs.dir(driverSrc)
  inputs.dir(apiDir)
  inputs.files(secretsFile)
  outputs.dir(coreJniOut)
  doFirst { work.deleteRecursively(); work.mkdirs() } // a fresh CMake cache: the sources may have moved
  workingDir(work)
  commandLine(
    "sh", "-c",
    "cmake -S '$apiDir' -B . -DSCRAPER_DRIVER=$driver -DCMAKE_BUILD_TYPE=Release " +
      "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY='${coreJniOut.get().asFile.resolve("native")}' >/dev/null && " +
      "cmake --build . --config Release",
  )
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(buildCoreJni.map { coreJniOut.get().asFile })

// --- the core as a static library per iOS target (macOS with Xcode only) ---
val iosTargets = mapOf(
  "iosArm64" to listOf("iphoneos", "arm64-apple-ios15.0"),
  "iosSimulatorArm64" to listOf("iphonesimulator", "arm64-apple-ios15.0-simulator"),
)
iosTargets.forEach { (target, sdkAndTriple) ->
  val (sdk, triple) = sdkAndTriple
  val task = tasks.register<Exec>("buildCoreIos${target.replaceFirstChar { it.uppercase() }}") {
    val out = layout.buildDirectory.dir("core-ios/$target").get().asFile
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    inputs.dir(driverSrc)
    inputs.dir(apiDir)
    inputs.files(secretsFile)
    outputs.dir(out)
    doFirst { out.mkdirs() }
    workingDir(out)
    val srcs = driverSrc.listFiles { f -> f.extension == "cpp" }!!.sorted().joinToString(" ") { "'$it'" }
    commandLine(
      "sh", "-c",
      "xcrun --sdk $sdk clang++ -std=c++17 -O2 -target $triple -I'$driverSrc' " +
        "-I'$apiDir' -c $srcs && xcrun ar rcs libcore.a *.o",
    )
  }
  tasks.matching { it.name == "cinteropCore${target.replaceFirstChar { c -> c.uppercase() }}" }
    .configureEach { dependsOn(task) }
}

tasks.withType<Test>().configureEach {
  // the simulated robot (python3) for the tests
  environment("SIMULATOR_SCRIPT", driverDir.resolve("tests/fake_robot_server.py").path)
  testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

if (hasAndroid) apply(from = "android.gradle")
