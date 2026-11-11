package nl.eschouten.scraper.app

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** iOS: UserDefaults of the app group, so the widget and Siri intents see the same data.
 *  Secrets (the PINs) go to the Keychain, in the app group's access group so the widget can
 *  read them too, readable after the first unlock (a widget runs in the background). Where
 *  the Keychain cannot be used (an unsigned simulator build) they stay in UserDefaults. */
class DefaultsStore(private val suiteName: String? = "group.nl.eschouten.scraper") : Store {
  private val d = suiteName?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults
  override fun getString(key: String): String? = d.stringForKey(key)
  override fun putString(key: String, value: String?) {
    if (value == null) d.removeObjectForKey(key) else d.setObject(value, forKey = key)
  }

  override fun getSecret(key: String): String? = Keychain.get(key, suiteName) ?: getString("secret.$key")

  override fun putSecret(key: String, value: String?) {
    val inKeychain = Keychain.put(key, value, suiteName)
    putString("secret.$key", if (inKeychain) null else value)
  }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private object Keychain {
  private const val SERVICE = "nl.eschouten.scraper"

  fun get(account: String, group: String?): String? = memScoped {
    val out = alloc<CFTypeRefVar>()
    val status = query(account, group, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) {
      SecItemCopyMatching(it, out.ptr)
    }
    if (status != errSecSuccess) return null
    val data = CFBridgingRelease(out.value) as? NSData ?: return null
    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
  }

  /** False if the Keychain could not keep it. */
  fun put(account: String, value: String?, group: String?): Boolean {
    query(account, group) { SecItemDelete(it) }
    if (value == null) return true
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
    val ref = CFBridgingRetain(data)
    try {
      return query(account, group, kSecValueData to ref, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock) {
        SecItemAdd(it, null)
      } == errSecSuccess
    } finally {
      CFRelease(ref)
    }
  }

  private fun <T> query(account: String, group: String?, vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
    val owned = mutableListOf<CFTypeRef?>()
    fun cf(s: String): CFTypeRef? = CFBridgingRetain(NSString.create(string = s)).also { owned += it }
    val q = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    try {
      CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
      CFDictionaryAddValue(q, kSecAttrService, cf(SERVICE))
      CFDictionaryAddValue(q, kSecAttrAccount, cf(account))
      if (group != null) CFDictionaryAddValue(q, kSecAttrAccessGroup, cf(group))
      for ((k, v) in extra) CFDictionaryAddValue(q, k, v)
      return block(q)
    } finally {
      owned.forEach { CFRelease(it) }
      CFRelease(q)
    }
  }
}
