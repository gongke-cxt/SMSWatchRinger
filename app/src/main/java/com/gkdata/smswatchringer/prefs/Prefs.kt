package com.gkdata.smswatchringer.prefs

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.gkdata.smswatchringer.sms.model.SmsEvent
import com.gkdata.smswatchringer.sms.model.SmsSource
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.text.RegexOption.MULTILINE

class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    data class KeywordRule(
        val keyword: String,
        val speakText: String? = null,
        val ringtoneEnabled: Boolean? = null,
        val repeatCount: Int? = null,
    )

    data class LastMatch(
        val from: String,
        val body: String,
        val receivedAtMillis: Long,
    )

    data class MatchResult(
        val matchedKeywords: List<String>,
        val matchedRegex: List<String>,
    ) {
        fun isMatch(): Boolean = matchedKeywords.isNotEmpty() || matchedRegex.isNotEmpty()
    }

    data class MatchRecord(
        val from: String,
        val body: String,
        val receivedAtMillis: Long,
        val source: SmsSource,
        val matchedKeywords: List<String>,
        val matchedRegex: List<String>,
        val alerted: Boolean,
    )

    fun isEnabled(): Boolean = sp.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun keywordsRaw(): String = sp.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS

    fun keywordRules(): List<KeywordRule> = parseKeywordRules(keywordsRaw())

    fun keywordsList(): List<String> = keywordRules().map { it.keyword }.distinct()

    fun keywordRuleMap(): Map<String, KeywordRule> = keywordRules().associateBy { it.keyword }

    fun allowedSendersRaw(): String = sp.getString(KEY_ALLOWED_SENDERS, "") ?: ""

    fun allowedSendersList(): List<String> =
        allowedSendersRaw()
            .split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun excludeKeywordsRaw(): String = sp.getString(KEY_EXCLUDE_KEYWORDS, "") ?: ""

    fun excludeKeywordsList(): List<String> =
        excludeKeywordsRaw()
            .split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun includeRegexRaw(): String = sp.getString(KEY_INCLUDE_REGEX, "") ?: ""

    fun includeRegexList(): List<String> =
        includeRegexRaw()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun openMatchPopup(): Boolean = sp.getBoolean(KEY_OPEN_MATCH_POPUP, true)

    fun ttsEnabled(): Boolean = sp.getBoolean(KEY_TTS_ENABLED, false)

    fun ttsRepeatCount(): Int =
        sp.getString(KEY_TTS_REPEAT_COUNT, DEFAULT_TTS_REPEAT_COUNT)
            ?.toIntOrNull()
            ?.coerceIn(1, 10)
            ?: DEFAULT_TTS_REPEAT_COUNT.toInt()

    fun ringtoneEnabled(): Boolean = sp.getBoolean(KEY_RINGTONE_ENABLED, true)

    fun alertMode(): AlertMode =
        AlertMode.fromValue(sp.getString(KEY_ALERT_MODE, AlertMode.CONTINUOUS_RINGTONE.value))


    fun continuousRingtoneUri(): Uri? =
        sp.getString(KEY_CONTINUOUS_RINGTONE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)

    fun setContinuousRingtoneUri(uri: Uri?) {
        sp.edit().putString(KEY_CONTINUOUS_RINGTONE, uri?.toString()).apply()
    }

    fun autoStopSeconds(): Long =
        sp.getString(KEY_AUTO_STOP_SECONDS, DEFAULT_AUTO_STOP_SECONDS)
            ?.toLongOrNull()
            ?: DEFAULT_AUTO_STOP_SECONDS.toLong()

    fun cooldownSeconds(): Long =
        sp.getString(KEY_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS)
            ?.toLongOrNull()
            ?: DEFAULT_COOLDOWN_SECONDS.toLong()

    fun lastMatch(): LastMatch? {
        val receivedAtMillis = sp.getLong(KEY_LAST_MATCH_AT, 0L)
        if (receivedAtMillis <= 0L) return null

        val from = sp.getString(KEY_LAST_MATCH_FROM, "") ?: ""
        val body = sp.getString(KEY_LAST_MATCH_BODY, "") ?: ""
        return LastMatch(from = from, body = body, receivedAtMillis = receivedAtMillis)
    }

    fun setLastMatch(event: SmsEvent) {
        sp.edit()
            .putLong(KEY_LAST_MATCH_AT, event.receivedAtMillis)
            .putString(KEY_LAST_MATCH_FROM, event.from)
            .putString(KEY_LAST_MATCH_BODY, event.body.take(400))
            .apply()
    }

    fun clearLastMatch() {
        sp.edit()
            .remove(KEY_LAST_MATCH_AT)
            .remove(KEY_LAST_MATCH_FROM)
            .remove(KEY_LAST_MATCH_BODY)
            .apply()
    }

    fun matchResult(event: SmsEvent): MatchResult {
        val allowedSenders = allowedSendersList()
        if (allowedSenders.isNotEmpty()) {
            val fromNormalized = normalizeSender(event.from)
            val ok = allowedSenders.any { fromNormalized.contains(normalizeSender(it)) }
            if (!ok) return MatchResult(emptyList(), emptyList())
        }

        val haystack = "${event.from}\n${event.body}".lowercase(Locale.ROOT)

        val exclude = excludeKeywordsList()
        if (exclude.isNotEmpty()) {
            val excluded = exclude.any { haystack.contains(it.lowercase(Locale.ROOT)) }
            if (excluded) return MatchResult(emptyList(), emptyList())
        }

        val keywords = keywordsList()
        val matchedKeywords =
            if (keywords.isEmpty()) {
                emptyList()
            } else {
                keywords.filter { haystack.contains(it.lowercase(Locale.ROOT)) }
            }

        val regexRules = includeRegexList()
        val matchedRegex =
            if (regexRules.isEmpty()) {
                emptyList()
            } else {
                regexRules.filter { rule ->
                    runCatching { Regex(rule, setOf(IGNORE_CASE, MULTILINE)) }
                        .getOrNull()
                        ?.containsMatchIn(haystack)
                        ?: false
                }
            }

        return MatchResult(matchedKeywords = matchedKeywords, matchedRegex = matchedRegex)
    }

    private fun normalizeSender(text: String): String =
        text.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("+", "")
            .replace("(", "")
            .replace(")", "")
            .lowercase(Locale.ROOT)

    fun matches(event: SmsEvent): Boolean = matchResult(event).isMatch()

    fun shouldSuppressByCooldown(event: SmsEvent): Boolean {
        if (event.source != SmsSource.BROADCAST) return false

        val cooldownMillis = cooldownSeconds().coerceAtLeast(0L) * 1000L
        if (cooldownMillis <= 0L) return false

        val lastAt = sp.getLong(KEY_LAST_ALERT_AT, 0L)
        val lastHash = sp.getString(KEY_LAST_ALERT_HASH, "") ?: ""
        if (lastAt <= 0L || lastHash.isBlank()) return false

        if (event.receivedAtMillis - lastAt > cooldownMillis) return false
        val currentHash = sha256Hex(normalizeForHash(event))
        return currentHash == lastHash
    }

    fun markAlertDispatched(event: SmsEvent) {
        if (event.source != SmsSource.BROADCAST) return

        sp.edit()
            .putLong(KEY_LAST_ALERT_AT, event.receivedAtMillis)
            .putString(KEY_LAST_ALERT_HASH, sha256Hex(normalizeForHash(event)))
            .apply()
    }

    fun matchHistory(): List<MatchRecord> {
        val raw = sp.getString(KEY_MATCH_HISTORY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val obj = json.optJSONObject(index) ?: continue
                    add(obj.toMatchRecordOrNull() ?: continue)
                }
            }
        }.getOrElse { emptyList() }
    }

    fun appendMatchHistory(
        event: SmsEvent,
        matchedKeywords: List<String>,
        matchedRegex: List<String>,
        alerted: Boolean,
    ) {
        val existing = matchHistory()

        val next = buildList {
            add(
                MatchRecord(
                    from = event.from,
                    body = event.body.take(800),
                    receivedAtMillis = event.receivedAtMillis,
                    source = event.source,
                    matchedKeywords = matchedKeywords.take(12),
                    matchedRegex = matchedRegex.take(8),
                    alerted = alerted,
                ),
            )
            addAll(existing)
        }.take(MAX_MATCH_HISTORY)

        sp.edit().putString(KEY_MATCH_HISTORY, next.toJsonArrayString()).apply()
    }

    fun clearMatchHistory() {
        sp.edit().remove(KEY_MATCH_HISTORY).apply()
    }

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_ALLOWED_SENDERS = "allowed_senders"
        const val KEY_EXCLUDE_KEYWORDS = "exclude_keywords"
        const val KEY_INCLUDE_REGEX = "include_regex"
        const val KEY_OPEN_MATCH_POPUP = "open_match_popup"
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_TTS_REPEAT_COUNT = "tts_repeat_count"
        const val KEY_RINGTONE_ENABLED = "ringtone_enabled"
        const val KEY_ALERT_MODE = "alert_mode"
        const val KEY_CONTINUOUS_RINGTONE = "alert_ringtone"
        const val KEY_AUTO_STOP_SECONDS = "auto_stop_seconds"
        const val KEY_COOLDOWN_SECONDS = "cooldown_seconds"

        private const val KEY_LAST_MATCH_AT = "last_match_at"
        private const val KEY_LAST_MATCH_FROM = "last_match_from"
        private const val KEY_LAST_MATCH_BODY = "last_match_body"

        private const val KEY_LAST_ALERT_AT = "last_alert_at"
        private const val KEY_LAST_ALERT_HASH = "last_alert_hash"

        private const val KEY_MATCH_HISTORY = "match_history_json"

        private const val DEFAULT_KEYWORDS = "交警\n贴单\n违停\n停车"
        private const val DEFAULT_TTS_REPEAT_COUNT = "1"
        private const val DEFAULT_AUTO_STOP_SECONDS = "0"
        private const val DEFAULT_COOLDOWN_SECONDS = "60"

        private const val MAX_MATCH_HISTORY = 50
    }
}

private fun parseKeywordRules(raw: String): List<Prefs.KeywordRule> {
    val parsed = buildList {
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val segments = line.split('|', '｜')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val head = segments.firstOrNull() ?: return@forEach
                val options = segments.drop(1)

                if (options.isEmpty() && !head.contains("=>")) {
                    val keywords = head.split(',', '，', ';', '；')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    if (keywords.size > 1) {
                        keywords.forEach { keyword -> add(Prefs.KeywordRule(keyword = keyword)) }
                        return@forEach
                    }
                }

                val (keyword, speakText) = parseKeywordHead(head) ?: return@forEach
                val (ringtoneEnabled, repeatCount) = parseKeywordOptions(options)
                add(
                    Prefs.KeywordRule(
                        keyword = keyword,
                        speakText = speakText,
                        ringtoneEnabled = ringtoneEnabled,
                        repeatCount = repeatCount,
                    ),
                )
            }
    }

    val deduped = LinkedHashMap<String, Prefs.KeywordRule>()
    for (rule in parsed) {
        val existing = deduped[rule.keyword]
        if (existing == null) {
            deduped[rule.keyword] = rule
        } else {
            val merged = Prefs.KeywordRule(
                keyword = existing.keyword,
                speakText = existing.speakText.takeUnless { it.isNullOrBlank() } ?: rule.speakText,
                ringtoneEnabled = existing.ringtoneEnabled ?: rule.ringtoneEnabled,
                repeatCount = existing.repeatCount ?: rule.repeatCount,
            )
            deduped[rule.keyword] = merged
        }
    }
    return deduped.values.toList()
}

private fun parseKeywordHead(text: String): Pair<String, String?>? {
    val index = text.indexOf("=>")
    if (index >= 0) {
        val keyword = text.substring(0, index).trim()
        if (keyword.isBlank()) return null
        val speakText = text.substring(index + 2).trim().takeIf { it.isNotBlank() }
        return keyword to speakText
    }

    val keyword = text.trim()
    if (keyword.isBlank()) return null
    return keyword to null
}

private fun parseKeywordOptions(options: List<String>): Pair<Boolean?, Int?> {
    var ringtoneEnabled: Boolean? = null
    var repeatCount: Int? = null

    for (opt in options) {
        val raw = opt.trim()
        if (raw.isBlank()) continue

        val lowered = raw.lowercase(Locale.ROOT)
        when (lowered) {
            "silent",
            "mute",
            "no_ring",
            "no-ring",
            "norings",
            "不响铃",
            "静音",
                -> ringtoneEnabled = false

            "ring",
            "响铃",
                -> ringtoneEnabled = true
        }

        val kv = splitKeyValue(raw) ?: continue
        val key = kv.first.lowercase(Locale.ROOT)
        val value = kv.second.trim()

        when (key) {
            "ring",
            "ringtone",
            "sound",
            "铃声",
            "响铃",
                -> parseBoolean(value)?.let { ringtoneEnabled = it }

            "repeat",
            "repeatcount",
            "重播",
            "次数",
                -> value.toIntOrNull()?.coerceIn(1, 10)?.let { repeatCount = it }
        }
    }

    return ringtoneEnabled to repeatCount
}

private fun splitKeyValue(text: String): Pair<String, String>? {
    val eq = text.indexOf('=').takeIf { it >= 0 }
    val colon = text.indexOf(':').takeIf { it >= 0 }
    val cnColon = text.indexOf('：').takeIf { it >= 0 }

    val idx =
        listOfNotNull(eq, colon, cnColon)
            .minOrNull()
            ?: return null

    val key = text.substring(0, idx).trim()
    val value = text.substring(idx + 1).trim()
    if (key.isBlank() || value.isBlank()) return null
    return key to value
}

private fun parseBoolean(text: String): Boolean? =
    when (text.trim().lowercase(Locale.ROOT)) {
        "1",
        "true",
        "on",
        "yes",
        "y",
        "开",
        "开启",
        "是",
        "响铃",
            -> true

        "0",
        "false",
        "off",
        "no",
        "n",
        "关",
        "关闭",
        "否",
        "不响铃",
            -> false

        else -> null
    }

private fun JSONObject.toMatchRecordOrNull(): Prefs.MatchRecord? {
    val from = optString("from", "")
    val body = optString("body", "")
    val receivedAtMillis = optLong("receivedAtMillis", 0L)
    val sourceRaw = optString("source", SmsSource.BROADCAST.name)
    val source = runCatching { SmsSource.valueOf(sourceRaw) }.getOrElse { SmsSource.BROADCAST }

    val matchedKeywords = optJSONArray("matchedKeywords")
        ?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index, "").trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }
        ?: emptyList()

    val matchedRegex = optJSONArray("matchedRegex")
        ?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index, "").trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }
        ?: emptyList()

    val alerted = optBoolean("alerted", true)

    if (receivedAtMillis <= 0L) return null
    return Prefs.MatchRecord(
        from = from,
        body = body,
        receivedAtMillis = receivedAtMillis,
        source = source,
        matchedKeywords = matchedKeywords,
        matchedRegex = matchedRegex,
        alerted = alerted,
    )
}

private fun List<Prefs.MatchRecord>.toJsonArrayString(): String {
    val json = JSONArray()
    for (record in this) {
        val obj = JSONObject()
            .put("from", record.from)
            .put("body", record.body)
            .put("receivedAtMillis", record.receivedAtMillis)
            .put("source", record.source.name)
            .put(
                "matchedKeywords",
                JSONArray().apply { record.matchedKeywords.forEach { put(it) } },
            )
            .put(
                "matchedRegex",
                JSONArray().apply { record.matchedRegex.forEach { put(it) } },
            )
            .put("alerted", record.alerted)
        json.put(obj)
    }
    return json.toString()
}

private fun normalizeForHash(event: SmsEvent): String =
    buildString {
        append(event.from.trim())
        append('\n')
        append(event.body.trim())
    }.lowercase(Locale.ROOT)

private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val result = CharArray(digest.size * 2)
    var j = 0
    for (byte in digest) {
        val value = byte.toInt() and 0xff
        val hi = value ushr 4
        val lo = value and 0x0f
        result[j++] = if (hi < 10) ('0'.code + hi).toChar() else ('a'.code + (hi - 10)).toChar()
        result[j++] = if (lo < 10) ('0'.code + lo).toChar() else ('a'.code + (lo - 10)).toChar()
    }
    return String(result)
}
