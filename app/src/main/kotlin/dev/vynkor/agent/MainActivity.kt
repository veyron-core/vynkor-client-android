package dev.vynkor.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.DeviceIdentity
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.PairingPayload
import dev.vynkor.agent.agent.ProfileStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ProfileAdapter

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show()
        } else {
            onPairingPayload(result.contents!!)
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
            else Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val statusDot = findViewById<TextView>(R.id.statusDot)
        val activeName = findViewById<TextView>(R.id.activeName)
        val activeHost = findViewById<TextView>(R.id.activeHost)
        val connect = findViewById<Button>(R.id.connect)
        val chat = findViewById<Button>(R.id.chat)
        val scan = findViewById<Button>(R.id.scan)
        val profiles = findViewById<RecyclerView>(R.id.profiles)
        val add = findViewById<FloatingActionButton>(R.id.addProfile)

        adapter = ProfileAdapter(
            onSelect = { profile ->
                ProfileStore.setActive(this, profile.id)
                refresh()
            },
            onEdit = { profile ->
                startActivity(
                    Intent(this, ProfileActivity::class.java)
                        .putExtra(ProfileActivity.EXTRA_PROFILE_ID, profile.id)
                )
            },
            onDelete = { profile ->
                ProfileStore.delete(this, profile.id)
                refresh()
            },
        )
        profiles.layoutManager = LinearLayoutManager(this)
        profiles.adapter = adapter

        add.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        connect.setOnClickListener {
            if (AgentHolder.agent != null) {
                AgentService.stop(this)
            } else {
                requestPermissions()
                AgentService.start(this)
            }
        }

        chat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        scan.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                launchScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        lifecycleScope.launch {
            AgentHolder.connectionState.collect { connected ->
                statusDot.setTextColor(
                    ContextCompat.getColor(this@MainActivity, if (connected) R.color.connected else R.color.disconnected)
                )
                statusText.setText(if (connected) R.string.status_connected else R.string.status_disconnected)
                connect.setText(if (connected) R.string.disconnect_button else R.string.connect_button)
            }
        }

        intent?.data?.let { onPairingPayload(it.toString()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { onPairingPayload(it.toString()) }
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
        )
    }

    private fun onPairingPayload(raw: String) {
        val profile = PairingPayload.parse(raw)
        if (profile == null) {
            Toast.makeText(this, R.string.scan_invalid, Toast.LENGTH_LONG).show()
            return
        }
        DeviceIdentity.setDeviceId(this, profile.deviceId)
        ProfileStore.save(this, profile)
        ProfileStore.setActive(this, profile.id)
        refresh()
        Toast.makeText(this, getString(R.string.paired_and_connected, profile.name), Toast.LENGTH_SHORT).show()
        if (AgentHolder.agent == null) {
            requestPermissions()
            AgentService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(ProfileStore.list(this))
        val active = ProfileStore.active(this)
        findViewById<TextView>(R.id.activeName).text =
            active?.name?.ifBlank { getString(R.string.unnamed_profile) } ?: getString(R.string.no_profile)
        findViewById<TextView>(R.id.activeHost).text = active?.hostUrl ?: ""
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
