package com.gkdata.smswatchringer.alert

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object TtsSpeaker {

    private data class PendingSpeech(
        val text: String,
        val repeatCount: Int,
    )

    private val initializing = AtomicBoolean(false)
    private val utteranceSeq = AtomicLong(0L)
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var pendingSpeech: PendingSpeech? = null

    fun speak(context: Context, text: String, repeatCount: Int = 1) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val repeats = repeatCount.coerceIn(1, 10)

        val instance = tts
        if (instance != null && ready) {
            speakInternal(instance, trimmed, repeats)
            return
        }

        pendingSpeech = PendingSpeech(text = trimmed, repeatCount = repeats)
        initIfNeeded(context.applicationContext)
    }

    fun stop() {
        tts?.runCatching { stop() }
    }

    private fun initIfNeeded(appContext: Context) {
        if (tts != null) return
        if (!initializing.compareAndSet(false, true)) return

        var created: TextToSpeech? = null
        created = TextToSpeech(appContext) { status ->
            val instance = created ?: return@TextToSpeech
            val ok = status == TextToSpeech.SUCCESS
            ready = ok
            if (!ok) {
                instance.runCatching { shutdown() }
                if (tts === instance) tts = null
                pendingSpeech = null
                return@TextToSpeech
            }

            instance.language = Locale.SIMPLIFIED_CHINESE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                instance.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }

            val pending = pendingSpeech
            if (pending != null && pending.text.isNotBlank()) {
                pendingSpeech = null
                speakInternal(instance, pending.text, pending.repeatCount)
            }
        }

        tts = created
        initializing.set(false)
    }

    private fun speakInternal(instance: TextToSpeech, text: String, repeatCount: Int) {
        runCatching { instance.stop() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (i in 1..repeatCount) {
                val queueMode = if (i == 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val utteranceId = "sms_match_${utteranceSeq.incrementAndGet()}"
                instance.speak(text, queueMode, null, utteranceId)
            }
        } else {
            @Suppress("DEPRECATION")
            instance.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            for (i in 2..repeatCount) {
                instance.speak(text, TextToSpeech.QUEUE_ADD, null)
            }
        }
    }
}
