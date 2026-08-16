package dev.vynkor.agent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.vynkor.agent.Agent
import dev.vynkor.agent.AgentConfig
import dev.vynkor.agent.AgentObserver
import dev.vynkor.agent.MainActivity
import dev.vynkor.agent.R
import dev.vynkor.agent.caps.BatteryProviderImpl
import dev.vynkor.agent.caps.ClipboardProviderImpl
import dev.vynkor.agent.caps.ContactsProviderImpl
import dev.vynkor.agent.caps.LocationProviderImpl
import dev.vynkor.agent.caps.SpeakerSinkImpl

/** Foreground service holding the agent connection. One per active host. */
class AgentService : Service() {
    private var agent: Agent? = null
    private val micCapture = MicCapture()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAgent()
            stopSelf()
            return START_NOT_STICKY
        }
        startAgent()
        return START_STICKY
    }

    private fun startAgent() {
        if (agent != null) return
        val profile = ProfileStore.active(this)
        if (profile == null || profile.hostUrl.isBlank()) {
            Log.w(TAG, "no host configured, stopping")
            stopSelf()
            return
        }
        val config = AgentConfig(
            hostUrl = profile.hostUrl,
            jwtToken = profile.jwtToken,
            jwtSecret = profile.jwtSecret,
            certPem = profile.certPem,
            deviceId = profile.deviceId,
            capabilities = listOf(
                "geo", "battery", "notifications", "clipboard", "contacts", "mic", "speaker", "chat"
            ),
            osVersion = Build.VERSION.RELEASE,
            arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            userId = profile.userId.ifBlank { "default" },
        )
        val a = Agent(config)
        a.setBattery(BatteryProviderImpl(this))
        a.setLocation(LocationProviderImpl(this))
        a.setClipboard(ClipboardProviderImpl(this))
        a.setContacts(ContactsProviderImpl(this))
        a.setSpeaker(SpeakerSinkImpl())
        a.setObserver(object : AgentObserver {
            override fun onStateChanged(connected: Boolean) {
                AgentHolder.connectionState.value = connected
            }
        })
        agent = a
        AgentHolder.agent = a
        a.start()
        micCapture.start(a, this)
    }

    private fun stopAgent() {
        micCapture.stop()
        agent?.stop()
        agent = null
        AgentHolder.agent = null
        AgentHolder.connectionState.value = false
    }

    override fun onDestroy() {
        stopAgent()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_stat_vynkor)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "vynkor_agent"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.vynkor.agent.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
