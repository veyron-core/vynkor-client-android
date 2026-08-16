package dev.vynkor.agent.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Persists the set of host profiles (JSON array in SharedPreferences) plus the
 * id of the active one. Migrates the pre-multi-host single-config keys on
 * first load.
 */
object ProfileStore {
    private const val PREFS = "vynkor_config"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"

    private const val KEY_HOST_URL = "host_url"
    private const val KEY_JWT = "jwt_token"
    private const val KEY_SECRET = "jwt_secret"

    fun list(context: Context): List<HostProfile> = load(context)

    fun get(context: Context, id: String): HostProfile? =
        load(context).firstOrNull { it.id == id }

    fun active(context: Context): HostProfile? {
        val profiles = load(context)
        val activeId = prefs(context).getString(KEY_ACTIVE, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun save(context: Context, profile: HostProfile) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persist(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == null) {
            setActive(context, profile.id)
        }
    }

    fun delete(context: Context, id: String) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == id }
        persist(context, list)
        if (prefs(context).getString(KEY_ACTIVE, null) == id) {
            prefs(context).edit().putString(KEY_ACTIVE, list.firstOrNull()?.id).apply()
        }
    }

    fun isConfigured(context: Context): Boolean =
        active(context)?.hostUrl?.isNotBlank() == true

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableList<HostProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null)
        if (raw.isNullOrBlank()) return migrateLegacy(context)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { HostProfile.fromJson(arr.getJSONObject(it)) }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun migrateLegacy(context: Context): MutableList<HostProfile> {
        val p = prefs(context)
        val legacyUrl = p.getString(KEY_HOST_URL, null) ?: return mutableListOf()
        val profile = HostProfile(
            name = "Default",
            hostUrl = legacyUrl,
            deviceId = DeviceIdentity.deviceId(context),
            jwtToken = p.getString(KEY_JWT, null) ?: "",
            jwtSecret = p.getString(KEY_SECRET, null) ?: "",
        )
        val list = mutableListOf(profile)
        persist(context, list)
        p.edit().remove(KEY_HOST_URL).remove(KEY_JWT).remove(KEY_SECRET).apply()
        return list
    }

    private fun persist(context: Context, list: List<HostProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }
}
