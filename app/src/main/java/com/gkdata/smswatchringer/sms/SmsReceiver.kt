package com.gkdata.smswatchringer.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.gkdata.smswatchringer.alert.AlertDispatcher
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.push.SmsCallbackClient
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

        val subscriptionId =
            intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        val event = SmsEvent(
            from = from,
            body = body,
            receivedAtMillis = System.currentTimeMillis(),
            source = SmsSource.BROADCAST,
            subscriptionId = subscriptionId,
        )

        val match = prefs.matchResult(event)
        if (prefs.callbackEnabled() && prefs.callbackUrl().isNotBlank() && match.shouldForward()) {
            val pending = goAsync()
            SmsCallbackClient.sendAsync(prefs, event) { pending.finish() }
        }

        if (!match.isMatch()) {
            Log.d(TAG, "no match (forwardOnly=${match.forwardAll}): from=$from, bodyLen=${body.length}")
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
