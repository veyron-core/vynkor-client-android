package dev.vynkor.agent.agent

import android.content.Context
import java.util.UUID

/** Stable per-install device id: random UUID persisted app-private. */
object DeviceIdentity {
    private const val PREFS = "vynkor_identity"
    private const val KEY_DEVICE_ID = "device_id"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** Override with an operator-chosen id (must match the minted JWT sub). */
    fun setDeviceId(context: Context, id: String) {
        if (id.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_ID, id.trim()).apply()
    }
}
