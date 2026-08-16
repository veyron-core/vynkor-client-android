package dev.vynkor.agent

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore

class ProfileActivity : AppCompatActivity() {

    private var editing: HostProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val name = findViewById<TextInputEditText>(R.id.name)
        val hostUrl = findViewById<TextInputEditText>(R.id.hostUrl)
        val deviceId = findViewById<TextInputEditText>(R.id.deviceId)
        val userId = findViewById<TextInputEditText>(R.id.userId)
        val jwt = findViewById<TextInputEditText>(R.id.jwt)
        val secret = findViewById<TextInputEditText>(R.id.secret)

        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        editing = id?.let { ProfileStore.get(this, it) }

        editing?.let { p ->
            name.setText(p.name)
            hostUrl.setText(p.hostUrl)
            deviceId.setText(p.deviceId)
            userId.setText(p.userId)
            jwt.setText(p.jwtToken)
            secret.setText(p.jwtSecret)
        } ?: run {
            deviceId.setText(DeviceIdentity.deviceId(this))
            userId.setText("default")
        }

        findViewById<android.widget.Button>(R.id.save).setOnClickListener {
            val profile = HostProfile(
                id = editing?.id ?: java.util.UUID.randomUUID().toString(),
                name = name.text?.toString()?.trim().orEmpty(),
                hostUrl = hostUrl.text?.toString()?.trim().orEmpty(),
                deviceId = deviceId.text?.toString()?.trim().orEmpty(),
                jwtToken = jwt.text?.toString()?.trim().orEmpty(),
                jwtSecret = secret.text?.toString()?.trim().orEmpty(),
                userId = userId.text?.toString()?.trim().orEmpty().ifBlank { "default" },
                // AI settings are no longer configured by hand: the host's `ai`
                // plugin is expected to declare its available models (see
                // docs/D14_AI_CHAT_AND_SETTINGS.md). Keep stored values when
                // editing so existing profiles are preserved.
                aiProvider = editing?.aiProvider ?: "openai",
                aiModel = editing?.aiModel.orEmpty(),
                aiBaseUrl = editing?.aiBaseUrl?.ifBlank { DEFAULT_AI_BASE_URL } ?: DEFAULT_AI_BASE_URL,
                aiApiKeyEnv = editing?.aiApiKeyEnv?.ifBlank { DEFAULT_AI_API_KEY_ENV } ?: DEFAULT_AI_API_KEY_ENV,
                aiAgent = editing?.aiAgent.orEmpty(),
            )
            if (profile.hostUrl.isBlank()) {
                Toast.makeText(this, R.string.host_url_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DeviceIdentity.setDeviceId(this, profile.deviceId)
            ProfileStore.save(this, profile)
            ProfileStore.setActive(this, profile.id)
            finish()
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val DEFAULT_AI_BASE_URL = "http://localhost:11434/v1"
        private const val DEFAULT_AI_API_KEY_ENV = "OLLAMA_API_KEY"
    }
}