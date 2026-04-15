package com.gkdata.smswatchringer.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.gkdata.smswatchringer.alert.AlarmService
import com.gkdata.smswatchringer.alert.TtsSpeaker
import com.gkdata.smswatchringer.databinding.ActivityMatchAlertBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MatchAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMatchAlertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val at = intent.getLongExtra(EXTRA_AT_MILLIS, 0L)
        val keywords = intent.getStringArrayExtra(EXTRA_KEYWORDS)?.toList().orEmpty()
        val regex = intent.getStringArrayExtra(EXTRA_REGEX)?.toList().orEmpty()

        binding.titleText.text =
            when {
                keywords.isNotEmpty() -> "命中关键词：${keywords.take(4).joinToString(separator = "，")}${if (keywords.size > 4) "…" else ""}"
                regex.isNotEmpty() -> "命中正则：${regex.take(2).joinToString(separator = "，")}${if (regex.size > 2) "…" else ""}"
                else -> "短信命中提醒"
            }

        val meta = buildString {
            if (at > 0L) append(formatTime(at)).append('\n')
            if (from.isNotBlank()) append("来源：").append(from).append('\n')
            if (keywords.isNotEmpty()) append("关键词：").append(keywords.joinToString(separator = "，")).append('\n')
            if (regex.isNotEmpty()) append("正则：").append(regex.joinToString(separator = "，")).append('\n')
        }.trim()

        binding.metaText.text = meta
        binding.bodyText.text = body

        binding.btnStop.setOnClickListener {
            AlarmService.stop(applicationContext)
            TtsSpeaker.stop()
            finish()
        }
        binding.btnDismiss.setOnClickListener { finish() }
    }

    private fun formatTime(millis: Long): String =
        runCatching {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrElse { "-" }

    companion object {
        const val EXTRA_FROM = "extra_from"
        const val EXTRA_BODY = "extra_body"
        const val EXTRA_AT_MILLIS = "extra_at_millis"
        const val EXTRA_KEYWORDS = "extra_keywords"
        const val EXTRA_REGEX = "extra_regex"
    }
}

