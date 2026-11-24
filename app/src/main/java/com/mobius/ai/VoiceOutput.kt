package com.mobius.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.mobius.core.FeatureFlag
import com.mobius.core.FeatureFlags
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * v6 workstream F — voice output (TTS), minimal functional.
 *
 * Speaks the assistant's SHORT result after it is shown (HomeScreen and
 * BubbleService.showOutcome). Android [TextToSpeech], bahasa id-ID bila
 * tersedia di perangkat, fallback ke locale default. Teks dipotong ke
 * [MAX_CHARS] supaya tidak membacakan jawaban panjang.
 *
 * SAFETY — yang dibacakan HANYA hasil singkat asisten (outcome.message).
 * Isi pesan orang lain (mis. teks pesan WhatsApp yang dibalas) TIDAK PERNAH
 * dibacakan dari sini, kecuali pengguna memintanya secara eksplisit — dan
 * jalur itu tidak ada di build ini: pemanggil hanya meneruskan
 * outcome.message, bukan body pesan.
 *
 * FLAG — [speak] no-op bila VOICE_OUTPUT OFF (perilaku v5: diam). [stop]
 * dipanggil saat tugas baru dimulai, supaya suara lama tidak menumpuk.
 * Tuan's phone has NO Nano; TTS ini murni Android TextToSpeech, tidak
 * bergantung pada Nano, cloud, atau jaringan.
 */
class VoiceOutput(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val featureFlags = FeatureFlags(appContext)
    private var tts: TextToSpeech? = null
    private var ready = false
    /** Spoken once the engine finishes init, if a speak() arrived earlier. */
    private var pending: String? = null

    fun start() {
        if (tts != null) return
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TextToSpeech unavailable", e)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        val engine = tts ?: return
        val indonesian = Locale("id", "ID")
        val available = try {
            engine.isLanguageAvailable(indonesian)
        } catch (_: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        engine.language =
            if (available >= TextToSpeech.LANG_AVAILABLE) indonesian else Locale.getDefault()
        ready = true
        pending?.let { text ->
            pending = null
            speakNow(text)
        }
    }

    /**
     * Speaks [text] (truncated to [MAX_CHARS]) when VOICE_OUTPUT is ON.
     * No-op when the flag is OFF, when the text is blank, or when TTS is
     * unavailable — never throws, never blocks the task outcome.
     */
    suspend fun speak(text: String) {
        if (!voiceOutputEnabled()) return
        val clean = text.trim().take(MAX_CHARS)
        if (clean.isEmpty()) return
        if (tts == null) start()
        if (!ready) {
            pending = clean
            return
        }
        speakNow(clean)
    }

    /** Stops any speech in progress. Called when a new task starts. */
    fun stop() {
        pending = null
        try {
            tts?.stop()
        } catch (_: Exception) {
            // TTS already gone; nothing to stop.
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // Already shut down.
        }
        tts = null
        ready = false
    }

    private fun speakNow(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
        }
    }

    /** Flag read that fails open to the default (ON), like MemoryStore. */
    private suspend fun voiceOutputEnabled(): Boolean =
        runCatching { featureFlags.isEnabled(FeatureFlag.VOICE_OUTPUT).first() }.getOrDefault(true)

    companion object {
        private const val TAG = "VoiceOutput"

        /** Max chars spoken per outcome — a short result, not a full answer. */
        const val MAX_CHARS = 500
        private const val UTTERANCE_ID = "mobius_outcome"
    }
}
