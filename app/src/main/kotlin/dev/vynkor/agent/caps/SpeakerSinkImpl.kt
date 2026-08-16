package dev.vynkor.agent.caps

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.vynkor.agent.SpeakerSink

/** Plays decoded PCM (s16le mono 16 kHz) through AudioTrack. */
class SpeakerSinkImpl : SpeakerSink {
    @Volatile
    private var track: AudioTrack? = null

    override fun playPcm(pcm: ByteArray) {
        var t = track
        if (t == null) {
            t = AudioTrack(
                AudioManager.STREAM_MUSIC,
                16_000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.getMinBufferSize(
                    16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ),
                AudioTrack.MODE_STREAM,
            )
            t.play()
            track = t
        }
        t.write(pcm, 0, pcm.size)
    }
}
