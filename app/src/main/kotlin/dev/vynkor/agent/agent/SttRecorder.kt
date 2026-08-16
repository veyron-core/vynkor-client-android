package dev.vynkor.agent.agent

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.ArrayList

/**
 * Minimal 16 kHz mono PCM recorder for local dictation. [start] opens an
 * [AudioRecord] and drains it on a background thread into a growable buffer;
 * [stop] stops, releases and returns the recorded samples as a FloatArray
 * in [-1, 1]. Tolerates [stop] when not recording.
 */
class SttRecorder {

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val samples = ArrayList<Short>()

    fun isRecording(): Boolean = recording

    fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf * 2, 8192)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }
        synchronized(samples) { samples.clear() }
        audioRecord = record
        recording = true
        recordThread = Thread({ readLoop(record) }, "stt-recorder").apply { start() }
    }

    /**
     * Stops and releases the recorder. Returns the recorded s16le samples
     * converted to FloatArray in [-1, 1]. Returns an empty array when the
     * recorder was not running.
     */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false
        val record = audioRecord ?: return FloatArray(0)
        try {
            recordThread?.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordThread = null
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            record.stop()
        }
        record.release()
        audioRecord = null
        return synchronized(samples) {
            val result = FloatArray(samples.size) { i -> samples[i] / 32768.0f }
            samples.clear()
            result
        }
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ShortArray(record.bufferSizeInFrames)
        record.startRecording()
        try {
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                synchronized(samples) {
                    for (i in 0 until read) samples.add(buffer[i])
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "mic read failed", t)
        }
    }

    companion object {
        private const val TAG = "SttRecorder"
        private const val SAMPLE_RATE = 16000
        private const val JOIN_TIMEOUT_MS = 1000L
    }
}
