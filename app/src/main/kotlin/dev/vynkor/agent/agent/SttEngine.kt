package dev.vynkor.agent.agent

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide, lazy on-device speech-to-text engine backed by sherpa-onnx.
 *
 * The recognizer construction is slow (seconds), so [ensureLoaded] performs
 * it exactly once on a background loader thread. The `onReady` callback is
 * always invoked on that loader thread once loading settles (success or
 * failure) — callers must check [isReady] and hop to the main thread for any
 * UI work. [transcribe] is blocking and CPU-bound: callers must invoke it on
 * their own IO dispatcher.
 */
class SttEngine private constructor(context: Context) {

    private val assets = context.assets
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stt-model-loader")
    }
    private val lock = Any()
    private val pendingCallbacks = ArrayDeque<() -> Unit>()
    private var loading = false

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    fun isReady(): Boolean = recognizer != null

    /**
     * Loads the recognizer on a background thread if not yet loaded. Invokes
     * [onReady] on the loader thread once loading settles. If the model is
     * already loaded the callback is still invoked on a background thread.
     */
    fun ensureLoaded(onReady: (() -> Unit)? = null) {
        if (isReady()) {
            if (onReady != null) loader.execute { onReady() }
            return
        }
        var start = false
        synchronized(lock) {
            if (isReady()) {
                if (onReady != null) loader.execute { onReady() }
            } else {
                if (onReady != null) pendingCallbacks.add(onReady)
                if (!loading) {
                    loading = true
                    start = true
                }
            }
        }
        if (start) loader.execute { loadModel() }
    }

    /**
     * Blocking, CPU-bound transcription of 16 kHz mono samples in [-1, 1].
     * Returns "" when the model is not loaded or transcription fails.
     * MUST be called on Dispatchers.IO, never on the main thread.
     */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val rec = recognizer ?: return ""
        return try {
            val stream: OfflineStream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            ""
        }
    }

    private fun loadModel() {
        var callbacks: List<() -> Unit> = emptyList()
        try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "stt/encoder.onnx",
                        decoder = "stt/decoder.onnx",
                        joiner = "stt/joiner.onnx",
                    ),
                    tokens = "stt/tokens.txt",
                    numThreads = NUM_THREADS,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(assetManager = assets, config = config)
            Log.i(TAG, "offline recognizer loaded")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load STT model", t)
            recognizer = null
        } finally {
            synchronized(lock) {
                loading = false
                callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
            }
        }
        for (callback in callbacks) {
            try {
                callback()
            } catch (t: Throwable) {
                Log.w(TAG, "ensureLoaded callback failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "SttEngine"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val NUM_THREADS = 2

        @Volatile
        private var instance: SttEngine? = null

        fun get(context: Context): SttEngine =
            instance ?: synchronized(this) {
                instance ?: SttEngine(context.applicationContext).also { instance = it }
            }
    }
}
