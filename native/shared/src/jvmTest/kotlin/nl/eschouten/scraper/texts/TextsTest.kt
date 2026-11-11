package nl.eschouten.scraper.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextsTest {
  @Test fun switchesLanguage() {
    useLanguage("en")
    assertEquals("Cancel", tr.cancel)
    assertEquals("Dry run A done", tr.dryRunDoneShort("A"))
    useLanguage("nl")
    assertEquals("Annuleren", tr.cancel)
    assertEquals("2 uur geleden", tr.hoursAgo(2))
    useLanguage("")
    assertTrue(tr.code in appLanguages)
  }

  /** Every text (property or function with text arguments) is filled in, in both languages. */
  @Test fun everyTextIsFilledInBothLanguages() {
    for (code in appLanguages) {
      val t = Tr(code)
      var n = 0
      for (m in Tr::class.java.declaredMethods) {
        if (m.returnType != String::class.java || !java.lang.reflect.Modifier.isPublic(m.modifiers)) continue
        val args = m.parameterTypes.map<Class<*>, Any> { p ->
          when (p) {
            String::class.java -> "X"
            Int::class.javaPrimitiveType -> 2
            Boolean::class.javaPrimitiveType -> true
            else -> "X"
          }
        }.toTypedArray()
        val s = m.invoke(t, *args) as String
        assertTrue(s.isNotBlank(), "${m.name} is empty in $code")
        n++
      }
      assertTrue(n > 200, "only $n texts found")
    }
  }
}
