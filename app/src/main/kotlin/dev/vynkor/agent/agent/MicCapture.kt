package dev.vynkor.agent.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Agent
import kotlin.concurrent.thread

/** Streams mic PCM (16 kHz mono s16le) to the agent for host STT. */
class MicCapture {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(agent: Agent, context: Context) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        running = true
        thread = thread(name = "vynkor-mic") {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 16_000)
            )
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            record.startRecording()
            val buf = ByteArray(640) // 20 ms at 16 kHz
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        agent.pushMicPcm(buf.copyOf(n))
                    }
                }
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }
}
