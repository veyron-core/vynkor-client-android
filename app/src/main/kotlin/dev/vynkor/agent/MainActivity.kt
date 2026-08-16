package dev.vynkor.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val statusDot = findViewById<TextView>(R.id.statusDot)
        val activeName = findViewById<TextView>(R.id.activeName)
        val activeHost = findViewById<TextView>(R.id.activeHost)
        val connect = findViewById<Button>(R.id.connect)
        val chat = findViewById<Button>(R.id.chat)
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

        lifecycleScope.launch {
            AgentHolder.connectionState.collect { connected ->
                statusDot.setTextColor(
                    ContextCompat.getColor(this@MainActivity, if (connected) R.color.connected else R.color.disconnected)
                )
                statusText.setText(if (connected) R.string.status_connected else R.string.status_disconnected)
                connect.setText(if (connected) R.string.disconnect_button else R.string.connect_button)
            }
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
