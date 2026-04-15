package com.gkdata.smswatchringer.alert

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gkdata.smswatchringer.prefs.AlertMode
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.sms.model.SmsEvent
import com.gkdata.smswatchringer.sms.model.SmsSource
import com.gkdata.smswatchringer.ui.MatchAlertActivity

object AlertDispatcher {

    fun dispatch(
        context: Context,
        prefs: Prefs,
        event: SmsEvent,
        matchedKeywords: List<String> = emptyList(),
        matchedRegex: List<String> = emptyList(),
    ) {
        if (event.source == SmsSource.BROADCAST) {
            prefs.setLastMatch(event)
        }
        prefs.appendMatchHistory(event, matchedKeywords, matchedRegex, alerted = true)
        prefs.markAlertDispatched(event)

        val primaryKeyword = matchedKeywords.firstOrNull()
        val primaryRule = primaryKeyword?.let { keyword -> prefs.keywordRuleMap()[keyword] }
        val ringtoneEnabled = prefs.ringtoneEnabled() && primaryRule?.ringtoneEnabled != false
        val ttsRepeatCount = (primaryRule?.repeatCount ?: prefs.ttsRepeatCount()).coerceIn(1, 10)

        if (prefs.ttsEnabled()) {
            val speech = buildSpeechText(event, primaryKeyword, primaryRule, matchedRegex)
            if (!speech.isNullOrBlank()) {
                runCatching { TtsSpeaker.speak(context, speech, ttsRepeatCount) }
            }
        }

        val soundUri = prefs.continuousRingtoneUri()
        NotificationHelper.ensureChannels(context, soundUri)
        val playSoundInNotification = ringtoneEnabled && prefs.alertMode() == AlertMode.NOTIFICATION
        NotificationHelper.postMatchNotification(
            context,
            event,
            soundUri,
            matchedKeywords,
            matchedRegex,
            playSoundInNotification,
            showPopup = prefs.openMatchPopup(),
        )

        if (prefs.openMatchPopup()) {
            runCatching { startAlertActivity(context, event, matchedKeywords, matchedRegex) }
        }

        if (ringtoneEnabled && prefs.alertMode() == AlertMode.CONTINUOUS_RINGTONE) {
            try {
                AlarmService.start(context, prefs, event, matchedKeywords, matchedRegex)
            } catch (t: Throwable) {
                Log.e(TAG, "start alarm failed: ${t.message}", t)
            }
        }
    }

    private fun startAlertActivity(
        context: Context,
        event: SmsEvent,
        matchedKeywords: List<String>,
        matchedRegex: List<String>,
    ) {
        val intent = Intent(context, MatchAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MatchAlertActivity.EXTRA_FROM, event.from)
            .putExtra(MatchAlertActivity.EXTRA_BODY, event.body)
            .putExtra(MatchAlertActivity.EXTRA_AT_MILLIS, event.receivedAtMillis)
            .putExtra(MatchAlertActivity.EXTRA_KEYWORDS, matchedKeywords.toTypedArray())
            .putExtra(MatchAlertActivity.EXTRA_REGEX, matchedRegex.toTypedArray())
        context.startActivity(intent)
    }

    private fun buildSpeechText(
        event: SmsEvent,
        primaryKeyword: String?,
        primaryRule: Prefs.KeywordRule?,
        matchedRegex: List<String>,
    ): String? {
        if (event.source == SmsSource.TEST) return "测试提醒"

        val speakOverride = primaryRule?.speakText?.trim().orEmpty()
        if (speakOverride.isNotBlank()) return speakOverride

        val keywordPreview = primaryKeyword?.trim().orEmpty()
        return when {
            keywordPreview.isNotBlank() -> "短信预警，命中关键词：$keywordPreview"
            matchedRegex.isNotEmpty() -> "短信预警，命中正则规则"
            else -> null
        }
    }

    private const val TAG = "AlertDispatcher"
}
