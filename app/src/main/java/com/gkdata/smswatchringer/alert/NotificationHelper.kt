package com.gkdata.smswatchringer.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gkdata.smswatchringer.R
import com.gkdata.smswatchringer.sms.model.SmsEvent
import com.gkdata.smswatchringer.sms.model.SmsSource
import com.gkdata.smswatchringer.ui.MainActivity
import com.gkdata.smswatchringer.ui.MatchAlertActivity

object NotificationHelper {

    const val NOTIFICATION_ID_SERVICE = 2001

    private const val CHANNEL_MATCH = "sms_match"
    private const val CHANNEL_MATCH_SILENT = "sms_match_silent"
    private const val CHANNEL_SERVICE = "alarm_service"

    fun ensureChannels(context: Context, matchSoundUri: Uri?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val desiredSound = matchSoundUri ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val existingMatch = manager.getNotificationChannel(CHANNEL_MATCH)
        if (existingMatch == null || existingMatch.sound != desiredSound) {
            if (existingMatch != null) {
                manager.deleteNotificationChannel(CHANNEL_MATCH)
            }

            val channel = NotificationChannel(
                CHANNEL_MATCH,
                context.getString(R.string.channel_match_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                setSound(desiredSound, attrs)
                description = context.getString(R.string.channel_match_desc)
            }
            manager.createNotificationChannel(channel)
        }

        if (manager.getNotificationChannel(CHANNEL_MATCH_SILENT) == null) {
            val silent = NotificationChannel(
                CHANNEL_MATCH_SILENT,
                context.getString(R.string.channel_match_silent_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                setSound(null, null)
                description = context.getString(R.string.channel_match_silent_desc)
            }
            manager.createNotificationChannel(silent)
        }

        if (manager.getNotificationChannel(CHANNEL_SERVICE) == null) {
            val channel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                description = context.getString(R.string.channel_service_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun postMatchNotification(
        context: Context,
        event: SmsEvent,
        soundUri: Uri?,
        matchedKeywords: List<String>,
        matchedRegex: List<String>,
        playSound: Boolean,
        showPopup: Boolean,
    ) {
        val titleRes =
            if (event.source == SmsSource.TEST) R.string.notif_test_title else R.string.notif_match_title

        val keywordText = matchedKeywords.takeIf { it.isNotEmpty() }?.let { keywords ->
            val preview = keywords.take(4).joinToString(separator = "，")
            if (keywords.size > 4) "关键词：$preview…" else "关键词：$preview"
        }

        val regexText = matchedRegex.takeIf { it.isNotEmpty() }?.let { rules ->
            val preview = rules.take(2).joinToString(separator = "，") { it.take(16) + if (it.length > 16) "…" else "" }
            if (rules.size > 2) "正则：$preview…" else "正则：$preview"
        }

        val titleText = keywordText ?: regexText ?: context.getString(titleRes)

        val bigText = buildString {
            if (!keywordText.isNullOrBlank()) append(keywordText).append('\n')
            if (!regexText.isNullOrBlank()) append(regexText).append('\n')
            append(event.from).append('\n')
            append(event.body)
        }

        val channelId = if (playSound) CHANNEL_MATCH else CHANNEL_MATCH_SILENT

        val openPending = if (showPopup) {
            val intent = Intent(context, MatchAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MatchAlertActivity.EXTRA_FROM, event.from)
                .putExtra(MatchAlertActivity.EXTRA_BODY, event.body)
                .putExtra(MatchAlertActivity.EXTRA_AT_MILLIS, event.receivedAtMillis)
                .putExtra(MatchAlertActivity.EXTRA_KEYWORDS, matchedKeywords.toTypedArray())
                .putExtra(MatchAlertActivity.EXTRA_REGEX, matchedRegex.toTypedArray())

            PendingIntent.getActivity(
                context,
                event.receivedAtMillis.toNotificationId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val stopPending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StopAlarmReceiver::class.java).setAction(StopAlarmReceiver.ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(titleText)
            .setContentText("${event.from}：${event.body}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .apply {
                if (playSound) {
                    setSound(soundUri ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
                } else {
                    setSilent(true)
                }
            }
            .setContentIntent(openPending)
            .apply {
                if (showPopup) {
                    setFullScreenIntent(openPending, true)
                }
            }
            .setAutoCancel(true)
            .addAction(R.drawable.ic_stat_stop, context.getString(R.string.notif_action_stop), stopPending)
            .build()

        NotificationManagerCompat.from(context).notify(event.receivedAtMillis.toNotificationId(), notification)
    }

    fun buildServiceNotification(context: Context, message: String): android.app.Notification {
        val stopPending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StopAlarmReceiver::class.java).setAction(StopAlarmReceiver.ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openPending = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_stat_stop, context.getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    private fun Long.toNotificationId(): Int {
        val mixed = this xor (this ushr 32)
        return (mixed and 0x7fffffff).toInt()
    }
}
