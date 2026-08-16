package dev.vynkor.agent.agent

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/**
 * Decodes the host-generated `vynkor://pair?d=<base64url(JSON)>` payload into a
 * [HostProfile]. The QR/link is a physical, unidirectional trusted channel, so
 * it may carry the frame-MAC secret and the served TLS cert for pinning.
 */
object PairingPayload {
    const val SCHEME = "vynkor"
    const val HOST = "pair"
    private const val VERSION = 1

    fun parse(raw: String): HostProfile? {
        val uri = Uri.parse(raw)
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val encoded = uri.getQueryParameter("d") ?: return null
        val json = try {
            String(Base64.decode(encoded, Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (o.optInt("v") != VERSION) return null
        if (o.optString("host_url").isBlank() || o.optString("jwt_token").isBlank()) return null

        return HostProfile(
            name = o.optString("name").ifBlank { o.optString("device_id") },
            hostUrl = o.optString("host_url"),
            deviceId = o.optString("device_id"),
            jwtToken = o.optString("jwt_token"),
            jwtSecret = o.optString("jwt_secret"),
            certPem = o.optString("cert_pem"),
        )
    }
}
