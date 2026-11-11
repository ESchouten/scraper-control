package nl.eschouten.scraper.app

import kotlinx.serialization.json.Json
import java.io.File

/** Desktop: one JSON file, by default ~/.config/scraper-control/settings.json. */
class FileStore(private val file: File = File(System.getProperty("user.home"), ".config/scraper-control/settings.json")) : Store {
  private val map: MutableMap<String, String> =
    runCatching { Json.decodeFromString<Map<String, String>>(file.readText()) }.getOrNull()?.toMutableMap() ?: mutableMapOf()

  @Synchronized override fun getString(key: String): String? = map[key]

  @Synchronized override fun putString(key: String, value: String?) {
    if (value == null) map.remove(key) else map[key] = value
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(Json.encodeToString(map))
    tmp.renameTo(file)
  }
}
