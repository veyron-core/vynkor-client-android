package dev.vynkor.agent.agent

import android.content.Context

/** Connection config for the agent: host URL + device JWT + host jwt_secret. */
object AgentConfigStore {
    private const val PREFS = "vynkor_config"
    private const val KEY_HOST_URL = "host_url"
    private const val KEY_JWT = "jwt_token"
    private const val KEY_SECRET = "jwt_secret"

    fun hostUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST_URL, null) ?: ""

    fun jwtToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JWT, null) ?: ""

    fun jwtSecret(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SECRET, null) ?: ""

    fun save(context: Context, hostUrl: String, jwt: String, secret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST_URL, hostUrl)
            .putString(KEY_JWT, jwt)
            .putString(KEY_SECRET, secret)
            .apply()
    }

    fun isConfigured(context: Context): Boolean =
        hostUrl(context).isNotBlank()
}
