package nl.eschouten.scraper.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Desktop: the core's JNI library ships as a resource (built by :shared:buildCoreJni). Once. */
internal fun loadCoreLibrary() = loaded

private val loaded: Unit by lazy {
  System.getProperty("scraper.core.lib")?.let { return@lazy System.load(it) }
  val name = System.mapLibraryName("scraper_jni")
  val res = CoreJni::class.java.getResourceAsStream("/native/$name")
    ?: error("core library /native/$name missing from the app")
  val dir = Files.createTempDirectory("scraper-core").toFile().apply { deleteOnExit() }
  val f = File(dir, name).apply { deleteOnExit() }
  res.use { Files.copy(it, f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
  System.load(f.absolutePath)
}
