package dev.vynkor.agent.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsEngine(context: Context) : TextToSpeech.OnInitListener {
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var ready = false

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun isSpeaking(): Boolean = tts.isSpeaking

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        ready = false
        tts.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.ERROR) return
        ready = true
        tts.language = Locale.getDefault()
    }
}
