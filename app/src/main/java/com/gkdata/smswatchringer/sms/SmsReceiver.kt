package com.gkdata.smswatchringer.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.gkdata.smswatchringer.alert.AlertDispatcher
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.sms.model.SmsEvent
import com.gkdata.smswatchringer.sms.model.SmsSource

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = Prefs(context)
        if (!prefs.isEnabled()) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val from = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
        if (body.isEmpty()) return

        val event = SmsEvent(
            from = from,
            body = body,
            receivedAtMillis = System.currentTimeMillis(),
            source = SmsSource.BROADCAST,
        )

        val match = prefs.matchResult(event)
        if (!match.isMatch()) {
            Log.d(TAG, "no match: from=$from, bodyLen=${body.length}")
            return
        }

        if (prefs.shouldSuppressByCooldown(event)) {
            prefs.setLastMatch(event)
            prefs.appendMatchHistory(event, match.matchedKeywords, match.matchedRegex, alerted = false)
            Log.d(TAG, "suppressed by cooldown: from=$from, bodyLen=${body.length}")
            return
        }

        AlertDispatcher.dispatch(context, prefs, event, match.matchedKeywords, match.matchedRegex)
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
