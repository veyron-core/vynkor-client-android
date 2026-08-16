package dev.vynkor.agent

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore

class ProfileActivity : AppCompatActivity() {

    private var editing: HostProfile? = null
    private var lastDefaultModel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val name = findViewById<TextInputEditText>(R.id.name)
        val hostUrl = findViewById<TextInputEditText>(R.id.hostUrl)
        val deviceId = findViewById<TextInputEditText>(R.id.deviceId)
        val userId = findViewById<TextInputEditText>(R.id.userId)
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
        val isNew = editing == null

        if (isNew) {
            lastDefaultModel = defaultModelFor("openai")
            model.setText(lastDefaultModel)
            baseUrl.setText(getString(R.string.default_ai_base_url))
            apiKeyEnv.setText(getString(R.string.default_api_key_env))
        }
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isNew) return
                val selected = provider.selectedItem as String
                val default = defaultModelFor(selected)
                val current = model.text?.toString()?.trim().orEmpty()
                if (current.isEmpty() || current == lastDefaultModel) {
                    model.setText(default)
                }
                lastDefaultModel = default
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val modelChips = listOf(
            findViewById<Chip>(R.id.chipLlama32),
            findViewById<Chip>(R.id.chipLlama31),
            findViewById<Chip>(R.id.chipGpt4oMini),
            findViewById<Chip>(R.id.chipClaudeSonnet),
            findViewById<Chip>(R.id.chipClaudeHaiku),
        )
        modelChips.forEach { chip ->
            chip.setOnClickListener { model.setText(chip.text) }
        }

        editing?.let { p ->
            name.setText(p.name)
            hostUrl.setText(p.hostUrl)
            deviceId.setText(p.deviceId)
            userId.setText(p.userId)
            jwt.setText(p.jwtToken)
            secret.setText(p.jwtSecret)
            model.setText(p.aiModel)
            baseUrl.setText(p.aiBaseUrl)
            apiKeyEnv.setText(p.aiApiKeyEnv)
            provider.setSelection(if (p.aiProvider == "anthropic") 1 else 0)
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

    private fun defaultModelFor(provider: String): String =
        HostProfile.DEFAULT_MODEL_BY_PROVIDER[provider] ?: getString(R.string.model_fallback)

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
