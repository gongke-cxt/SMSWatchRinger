package com.gkdata.smswatchringer.push

import android.util.Log
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.sms.model.SmsEvent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

object SmsCallbackClient {

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sms-callback").apply { isDaemon = true }
        }

    data class ProbeResult(
        val ok: Boolean,
        val code: Int? = null,
        val message: String? = null,
    )

    fun probeAsync(
        endpoint: String,
        prefs: Prefs,
        event: SmsEvent,
        onResult: (ProbeResult) -> Unit,
    ) {
        val normalized = endpoint.trim()
        if (normalized.isBlank()) {
            onResult(ProbeResult(ok = false, message = "empty endpoint"))
            return
        }

        executor.execute {
            try {
                onResult(probeNow(prefs, event, normalized))
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed: ${t.message}", t)
                onResult(ProbeResult(ok = false, message = t.message))
            }
        }
    }

    fun sendAsync(
        prefs: Prefs,
        event: SmsEvent,
        onDone: (() -> Unit)? = null,
    ) {
        val endpoint = prefs.callbackUrl().trim()
        if (endpoint.isBlank()) {
            onDone?.invoke()
            return
        }

        executor.execute {
            try {
                sendNow(prefs, event, endpoint)
            } catch (t: Throwable) {
                Log.w(TAG, "callback failed: ${t.message}", t)
            } finally {
                onDone?.invoke()
            }
        }
    }

    private fun sendNow(
        prefs: Prefs,
        event: SmsEvent,
        endpoint: String,
    ) {
        val payload = buildPayload(prefs, event)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "*/*")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
        }

        connection.outputStream.use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            val errorBody =
                runCatching { connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
            Log.w(TAG, "callback http=$code, body=${errorBody.take(300)}")
        }

        connection.disconnect()
    }

    private fun probeNow(
        prefs: Prefs,
        event: SmsEvent,
        endpoint: String,
    ): ProbeResult {
        val payload = buildPayload(prefs, event)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("accept", "*/*")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
        }

        try {
            connection.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            if (code in 200..299) return ProbeResult(ok = true, code = code)

            val errorBody =
                runCatching { connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                    .takeIf { it.isNotBlank() }

            return ProbeResult(ok = false, code = code, message = errorBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPayload(prefs: Prefs, event: SmsEvent): String {
        val json = JSONObject()
            .put("imei", prefs.callbackImei())
            .put("phoneNumber", prefs.callbackPhoneNumber(event.subscriptionId))
            .put("senderNumber", event.from)
            .put("content", event.body)
            .put("receiveTime", formatUtc(event.receivedAtMillis))
        return json.toString()
    }

    private fun formatUtc(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    private const val TAG = "SmsCallbackClient"
}
