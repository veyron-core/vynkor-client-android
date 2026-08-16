package dev.vynkor.agent

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.vynkor.agent.agent.AgentConfigStore
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val hostUrl = findViewById<EditText>(R.id.hostUrl)
        val jwt = findViewById<EditText>(R.id.jwt)
        val secret = findViewById<EditText>(R.id.secret)
        val deviceId = findViewById<EditText>(R.id.deviceId)
        val status = findViewById<TextView>(R.id.status)
        val connect = findViewById<Button>(R.id.connect)
        val notificationButton = findViewById<Button>(R.id.notificationAccess)

        hostUrl.setText(AgentConfigStore.hostUrl(this))
        jwt.setText(AgentConfigStore.jwtToken(this))
        secret.setText(AgentConfigStore.jwtSecret(this))
        deviceId.setText(dev.vynkor.agent.agent.DeviceIdentity.deviceId(this))

        connect.setOnClickListener {
            AgentConfigStore.save(
                this,
                hostUrl.text.toString(),
                jwt.text.toString(),
                secret.text.toString(),
            )
            // must match the minted JWT sub claim
            dev.vynkor.agent.agent.DeviceIdentity.setDeviceId(this, deviceId.text.toString())
            requestPermissions()
            AgentService.start(this)
            status.text = getString(R.string.status_connecting)
        }

        notificationButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val connected = AgentHolder.agent?.isConnected() == true
        findViewById<TextView>(R.id.status).text =
            getString(if (connected) R.string.status_connected else R.string.status_disconnected)
    }

    private fun requestPermissions() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
        }
    }

    companion object {
        private val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
