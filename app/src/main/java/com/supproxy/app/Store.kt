package com.supproxy.app

import android.content.Context
import android.content.Intent
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

    fun active(c: Context): Profile =
        p(c).getString("active", null)?.let { Profile.fromJson(JSONObject(it)) } ?: Profile()

    fun setActive(c: Context, pr: Profile) = p(c).edit().putString("active", pr.toJson().toString()).apply()

    fun saved(c: Context): MutableList<Profile> {
        val arr = JSONArray(p(c).getString("saved", "[]"))
        return MutableList(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
    }

    fun setSaved(c: Context, l: List<Profile>) {
        val arr = JSONArray()
        l.forEach { arr.put(it.toJson()) }
        p(c).edit().putString("saved", arr.toString()).apply()
    }

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
