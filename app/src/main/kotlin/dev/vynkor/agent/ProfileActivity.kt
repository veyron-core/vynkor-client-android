package dev.vynkor.agent

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
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
        val jwt = findViewById<TextInputEditText>(R.id.jwt)
        val secret = findViewById<TextInputEditText>(R.id.secret)
        val provider = findViewById<Spinner>(R.id.provider)
        val model = findViewById<TextInputEditText>(R.id.model)
        val baseUrl = findViewById<TextInputEditText>(R.id.baseUrl)
        val apiKeyEnv = findViewById<TextInputEditText>(R.id.apiKeyEnv)

        provider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("openai", "anthropic"),
        )

        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        editing = id?.let { ProfileStore.get(this, it) }

        editing?.let { p ->
            name.setText(p.name)
            hostUrl.setText(p.hostUrl)
            deviceId.setText(p.deviceId)
            jwt.setText(p.jwtToken)
            secret.setText(p.jwtSecret)
            model.setText(p.aiModel)
            baseUrl.setText(p.aiBaseUrl)
            apiKeyEnv.setText(p.aiApiKeyEnv)
            provider.setSelection(if (p.aiProvider == "anthropic") 1 else 0)
        } ?: run {
            deviceId.setText(DeviceIdentity.deviceId(this))
        }

        findViewById<android.widget.Button>(R.id.save).setOnClickListener {
            val profile = HostProfile(
                id = editing?.id ?: java.util.UUID.randomUUID().toString(),
                name = name.text?.toString()?.trim().orEmpty(),
                hostUrl = hostUrl.text?.toString()?.trim().orEmpty(),
                deviceId = deviceId.text?.toString()?.trim().orEmpty(),
                jwtToken = jwt.text?.toString()?.trim().orEmpty(),
                jwtSecret = secret.text?.toString()?.trim().orEmpty(),
                aiProvider = provider.selectedItem as String,
                aiModel = model.text?.toString()?.trim().orEmpty(),
                aiBaseUrl = baseUrl.text?.toString()?.trim().orEmpty(),
                aiApiKeyEnv = apiKeyEnv.text?.toString()?.trim().orEmpty(),
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
    }
}
