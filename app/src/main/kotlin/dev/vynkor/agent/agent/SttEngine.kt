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
 * The bundled `zipformer-ru-int8` model is **offline-only** — its metadata
 * lacks the streaming `encoder_dims`, so `OnlineRecognizer` rejects it. "Live"
 * dictation is therefore emulated: audio accumulates in an [SttSession] and
 * [partial] re-decodes everything spoken so far (the caller throttles it to
 * ~1 s), producing a near-real-time transcript that grows while the user
 * speaks. [finish] returns the final text and frees the session.
 *
 * The recognizer construction is slow (seconds), so [ensureLoaded] performs
 * it exactly once on a background loader thread. The `onReady` callback is
 * always invoked on that loader thread once loading settles (success or
 * failure) — callers must check [isReady] and hop to the main thread for any
 * UI work. All decode methods are blocking and CPU-bound: call them on
 * `Dispatchers.IO`, never the main thread.
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

    /** Opens a new dictation session. Null when the model is not loaded. */
    fun newSession(): SttSession? = if (isReady()) SttSession() else null

    /** Appends a chunk of 16 kHz mono samples in [-1, 1]. Cheap, any thread. */
    fun feed(session: SttSession, chunk: FloatArray) {
        if (chunk.isEmpty()) return
        synchronized(session.lock) {
            if (session.finished) return
            for (s in chunk) session.samples.add(s)
        }
    }

    /**
     * Decodes everything spoken so far and returns the current transcript
     * (grows as the user keeps talking). CPU-bound: call on Dispatchers.IO.
     */
    fun partial(session: SttSession): String = transcribe(session)

    /**
     * Marks the session done, returns the final transcript and frees the
     * accumulated audio. CPU-bound: call on Dispatchers.IO.
     */
    fun finish(session: SttSession): String {
        synchronized(session.lock) { session.finished = true }
        val text = transcribe(session)
        synchronized(session.lock) { session.samples.clear() }
        return text
    }

    private fun transcribe(session: SttSession): String {
        val rec = recognizer ?: return ""
        val samples = synchronized(session.lock) {
            if (session.samples.isEmpty()) FloatArray(0) else session.samples.toFloatArray()
        }
        if (samples.isEmpty()) return ""
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

/**
 * One dictation session: the audio accumulated so far. Fed from the recorder
 * thread, decoded on an IO thread; [SttSession.finished] stops further
 * accumulation after [SttEngine.finish].
 */
class SttSession internal constructor() {
    internal val lock = Any()
    internal val samples = ArrayList<Float>()
    internal var finished = false
}
