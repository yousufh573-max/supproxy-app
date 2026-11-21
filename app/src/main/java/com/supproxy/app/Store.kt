package com.supproxy.app

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class Profile(
    var name: String = "New Profile",
    var protocol: String = "SOCKS5",
    var host: String = "",
    var port: Int = 1080,
    var auth: Boolean = false,
    var user: String = "",
    var pass: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("protocol", protocol).put("host", host)
        .put("port", port).put("auth", auth).put("user", user).put("pass", pass)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            o.optString("name", "Profile"), o.optString("protocol", "SOCKS5"), o.optString("host"),
            o.optInt("port", 1080), o.optBoolean("auth"), o.optString("user"), o.optString("pass")
        )
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("supproxy", Context.MODE_PRIVATE)

    private const val KEY_ALIAS = "supproxy.profiles.v1"
    @Volatile var storageUnavailable = false
        private set

    fun active(c: Context): Profile =
        readProtected(c, "active")?.let { Profile.fromJson(JSONObject(it)) } ?: Profile()

    fun setActive(c: Context, pr: Profile): Boolean = writeProtected(c, "active", pr.toJson().toString())

    fun saved(c: Context): MutableList<Profile> {
        val arr = JSONArray(readProtected(c, "saved") ?: "[]")
        return MutableList(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
    }

    fun setSaved(c: Context, profiles: List<Profile>): Boolean {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        return writeProtected(c, "saved", arr.toString())
    }

    @Synchronized
    private fun readProtected(c: Context, name: String): String? {
        val prefs = p(c)
        val encrypted = prefs.getString("${name}_encrypted", null)
        if (encrypted == null) {
            val legacy = prefs.getString(name, null) ?: return null
            return legacy.takeIf { writeProtected(c, name, it) }
        }
        return try {
            val parts = encrypted.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD(name.toByteArray())
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            storageUnavailable = true
            null
        }
    }

    @Synchronized
    private fun writeProtected(c: Context, name: String, value: String): Boolean {
        if (storageUnavailable) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(name.toByteArray())
            val encrypted = "${encode(cipher.iv)}:${encode(cipher.doFinal(value.toByteArray()))}"
            p(c).edit().putString("${name}_encrypted", encrypted).remove(name).commit().also {
                if (!it) storageUnavailable = true
            }
        } catch (_: Exception) {
            storageUnavailable = true
            false
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Profile encryption key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    fun appLock(c: Context) = p(c).getBoolean("app_lock", false)
    fun setAppLock(c: Context, enabled: Boolean) = p(c).edit().putBoolean("app_lock", enabled).apply()
    fun protectWifi(c: Context) = p(c).getBoolean("protect_wifi", false)
    fun setProtectWifi(c: Context, enabled: Boolean) = p(c).edit().putBoolean("protect_wifi", enabled).apply()

    fun routablePackages(c: Context): List<String> =
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != c.packageName }

    fun dnsOverTcp(c: Context) = p(c).getBoolean("dns_over_tcp", false)
    fun setDnsOverTcp(c: Context, enabled: Boolean) = p(c).edit().putBoolean("dns_over_tcp", enabled).apply()

    fun excluded(c: Context): MutableSet<String> = HashSet(p(c).getStringSet("excluded", emptySet())!!)
    fun setExcluded(c: Context, s: Set<String>) = p(c).edit().putStringSet("excluded", HashSet(s)).apply()

    fun autoReconnect(c: Context) = p(c).getBoolean("auto", true)
    fun setAutoReconnect(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()

    fun wholePhone(c: Context) = p(c).getBoolean("whole", true)
    fun setWholePhone(c: Context, v: Boolean) = p(c).edit().putBoolean("whole", v).apply()
}
