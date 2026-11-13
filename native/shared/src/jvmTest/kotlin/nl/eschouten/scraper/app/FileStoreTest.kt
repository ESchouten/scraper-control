package nl.eschouten.scraper.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileStoreTest {
  private fun dir() = Files.createTempDirectory("filestore").toFile()

  @Test fun everySaveReplacesTheFile() {
    val f = File(dir(), "settings.json")
    FileStore(f).apply {
      putString("a", "1")
      putString("b", "2")   // the second save once got lost on Windows
      putString("a", null)
    }
    val back = FileStore(f)
    assertEquals(null, back.getString("a"))
    assertEquals("2", back.getString("b"))
    assertEquals(false, File(f.path + ".tmp").exists())
  }

  @Test fun aNewerTmpFromALostSaveIsUsed() {
    val f = File(dir(), "settings.json")
    f.writeText("""{"robots":"old"}""")
    f.setLastModified(1_000_000L)
    File(f.path + ".tmp").writeText("""{"robots":"new"}""")
    assertEquals("new", FileStore(f).getString("robots"))
  }
}
