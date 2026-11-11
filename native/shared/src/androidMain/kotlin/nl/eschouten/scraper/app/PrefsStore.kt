package nl.eschouten.scraper.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android: SharedPreferences, also read by the home-screen widgets. Secrets (the PINs) are
 *  encrypted with an AES key that never leaves the Android Keystore. */
class PrefsStore(context: Context) : Store {
  private val prefs = context.applicationContext.getSharedPreferences("scraper", Context.MODE_PRIVATE)
  override fun getString(key: String): String? = prefs.getString(key, null)
  override fun putString(key: String, value: String?) {
    prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
  }

  override fun getSecret(key: String): String? {
    val v = prefs.getString("secret.$key", null) ?: return null
    return runCatching {
      val b = Base64.decode(v, Base64.NO_WRAP)
      val c = Cipher.getInstance(TRANSFORM)
      c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV))
      String(c.doFinal(b, IV, b.size - IV), Charsets.UTF_8)
    }.getOrNull() // e.g. restored from a backup without the key: ask for the PIN again
  }

  override fun putSecret(key: String, value: String?) {
    if (value == null) return putString("secret.$key", null)
    val c = Cipher.getInstance(TRANSFORM)
    c.init(Cipher.ENCRYPT_MODE, key())
    val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
    putString("secret.$key", Base64.encodeToString(out, Base64.NO_WRAP))
  }

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    g.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return g.generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "scraper-secrets"
    const val TRANSFORM = "AES/GCM/NoPadding"
    const val IV = 12 // bytes, what the Keystore uses for GCM
  }
}
