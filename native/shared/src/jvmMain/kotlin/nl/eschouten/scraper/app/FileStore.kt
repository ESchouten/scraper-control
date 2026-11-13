package nl.eschouten.scraper.app

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Desktop: one JSON file, by default ~/.config/scraper-control/settings.json. Saved through a
 *  .tmp next to it that replaces the file (File.renameTo cannot replace a file on Windows). */
class FileStore(private val file: File = File(System.getProperty("user.home"), ".config/scraper-control/settings.json")) : Store {
  private val tmp = File(file.path + ".tmp")

  // a .tmp newer than the file: a save that did not get to replace it (older versions on Windows)
  private val map: MutableMap<String, String> =
    listOf(tmp, file).filter { it.isFile }.sortedByDescending { it.lastModified() }.firstNotNullOfOrNull { f ->
      runCatching { Json.decodeFromString<Map<String, String>>(f.readText()) }.getOrNull()
    }?.toMutableMap() ?: mutableMapOf()

  @Synchronized override fun getString(key: String): String? = map[key]

  @Synchronized override fun putString(key: String, value: String?) {
    if (value == null) map.remove(key) else map[key] = value
    file.parentFile?.mkdirs()
    tmp.writeText(Json.encodeToString(map))
    try {
      Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
    }
  }
}
