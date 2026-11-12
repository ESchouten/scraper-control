// Where the apps keep their settings and logbook: a small key-value store per platform
// (Android SharedPreferences, iOS UserDefaults in the app group so the widget sees it too,
// desktop a file). Tests use MemoryStore.
package nl.eschouten.scraper.app

interface Store {
  fun getString(key: String): String?

  /** null removes the key. */
  fun putString(key: String, value: String?)

  /** A secret (a robot's PIN). The phones keep it encrypted: Android with a key in the
   *  Android Keystore, iOS in the Keychain. Elsewhere (desktop, tests) a normal value. */
  fun getSecret(key: String): String? = getString("secret.$key")

  /** null removes it. */
  fun putSecret(key: String, value: String?) = putString("secret.$key", value)
}

class MemoryStore(initial: Map<String, String> = emptyMap()) : Store {
  private val map = initial.toMutableMap()
  override fun getString(key: String): String? = map[key]
  override fun putString(key: String, value: String?) {
    if (value == null) map.remove(key) else map[key] = value
  }
}
