package com.gkdata.smswatchringer.alert

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.sms.model.SmsEvent

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var currentMessage: String = ""
    private val stopHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var stopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentMessage = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
                val ringtoneUri = intent.getStringExtra(EXTRA_RINGTONE_URI)?.let(Uri::parse)
                val autoStopMillis = intent.getLongExtra(EXTRA_AUTO_STOP_MILLIS, 0L)
                startForeground(
                    NotificationHelper.NOTIFICATION_ID_SERVICE,
                    NotificationHelper.buildServiceNotification(this, currentMessage),
                )
                runCatching { startPlayback(ringtoneUri) }
                    .onFailure {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                scheduleAutoStop(autoStopMillis)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelAutoStop()
        stopPlayback()
        super.onDestroy()
    }

    private fun startPlayback(ringtoneUri: Uri?) {
        stopPlayback()
        val uri = ringtoneUri ?: Settings.System.DEFAULT_ALARM_ALERT_URI

        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        player.isLooping = true
        player.setDataSource(this, uri)
        player.prepare()
        player.start()
        mediaPlayer = player
    }

    private fun scheduleAutoStop(autoStopMillis: Long) {
        cancelAutoStop()
        if (autoStopMillis <= 0L) return

        val runnable = Runnable { stopSelf() }
        stopRunnable = runnable
        stopHandler.postDelayed(runnable, autoStopMillis)
    }

    private fun cancelAutoStop() {
        stopRunnable?.let { stopHandler.removeCallbacks(it) }
        stopRunnable = null
    }

    private fun stopPlayback() {
        mediaPlayer?.run {
            try {
                stop()
            } catch (_: Throwable) {
                // ignore
            }
            release()
        }
        mediaPlayer = null
    }

    companion object {
        const val ACTION_START = "com.gkdata.smswatchringer.action.ALARM_START"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val EXTRA_RINGTONE_URI = "extra_ringtone_uri"
        private const val EXTRA_AUTO_STOP_MILLIS = "extra_auto_stop_millis"

        fun start(
            context: Context,
            prefs: Prefs,
            event: SmsEvent,
            matchedKeywords: List<String>,
            matchedRegex: List<String>,
        ) {
            val keywordLine = matchedKeywords.takeIf { it.isNotEmpty() }?.let { keywords ->
                val preview = keywords.take(6).joinToString(separator = "，")
                if (keywords.size > 6) "关键词：$preview…" else "关键词：$preview"
            }

            val regexLine = matchedRegex.takeIf { it.isNotEmpty() }?.let { rules ->
                val preview = rules.take(3).joinToString(separator = "，") { it.take(16) + if (it.length > 16) "…" else "" }
                if (rules.size > 3) "正则：$preview…" else "正则：$preview"
            }

            val message = buildString {
                if (!keywordLine.isNullOrBlank()) append(keywordLine).append('\n')
                if (!regexLine.isNullOrBlank()) append(regexLine).append('\n')
                append(event.from).append('\n')
                append(event.body)
            }

            val startIntent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_RINGTONE_URI, prefs.continuousRingtoneUri()?.toString())
                .putExtra(EXTRA_AUTO_STOP_MILLIS, prefs.autoStopSeconds().coerceAtLeast(0L) * 1000L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, startIntent)
            } else {
                context.startService(startIntent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
