package com.gkdata.smswatchringer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.gkdata.smswatchringer.BuildConfig
import com.gkdata.smswatchringer.R
import com.gkdata.smswatchringer.alert.AlertDispatcher
import com.gkdata.smswatchringer.alert.AlarmService
import com.gkdata.smswatchringer.alert.NotificationHelper
import com.gkdata.smswatchringer.alert.TtsSpeaker
import com.gkdata.smswatchringer.prefs.AlertMode
import com.gkdata.smswatchringer.databinding.ActivityMainBinding
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.sms.model.SmsEvent
import com.gkdata.smswatchringer.sms.model.SmsSource
import com.gkdata.smswatchringer.ui.home.HomeActionId
import com.gkdata.smswatchringer.ui.home.HomeActionItem
import com.gkdata.smswatchringer.ui.home.HomeActionTone
import com.gkdata.smswatchringer.ui.home.HomeActionsAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var openSettingsAfterSmsGrant = false
    private var currentStatusDetails: String = ""

    private val actionsAdapter = HomeActionsAdapter(::onActionClick)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateUi()

            if (openSettingsAfterSmsGrant && hasPermission(Manifest.permission.RECEIVE_SMS)) {
                openSettingsAfterSmsGrant = false
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val pickedUri = result.data?.let { intent ->
                getParcelableUriExtra(intent, RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            prefs.setContinuousRingtoneUri(pickedUri)
            NotificationHelper.ensureChannels(this, pickedUri)
            updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        setSupportActionBar(binding.toolbar)

        val gridLayoutManager = GridLayoutManager(this, 2)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = actionsAdapter.spanAt(position)
        }
        binding.actionGrid.layoutManager = gridLayoutManager
        binding.actionGrid.adapter = actionsAdapter

        binding.switchEnable.isChecked = prefs.isEnabled()
        binding.switchEnable.setOnCheckedChangeListener { _, checked ->
            prefs.setEnabled(checked)
            updateUi()
        }

        binding.statusCard.setOnClickListener { showStatusDetails() }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        binding.switchEnable.isChecked = prefs.isEnabled()
        updateUi()
    }

    private fun updateUi() {
        val smsGranted = hasPermission(Manifest.permission.RECEIVE_SMS)
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        val ringtoneEnabled = prefs.ringtoneEnabled()
        val ringtoneName = getRingtoneTitle(prefs.continuousRingtoneUri())
        val alertModeLabel =
            if (!ringtoneEnabled) {
                "不响铃"
            } else {
                when (prefs.alertMode()) {
                    AlertMode.NOTIFICATION -> "仅通知"
                    AlertMode.CONTINUOUS_RINGTONE -> "连续响铃"
                }
            }
        val autoStopLabel =
            if (prefs.autoStopSeconds() <= 0L) "关闭" else formatSeconds(prefs.autoStopSeconds())
        val cooldownLabel =
            if (prefs.cooldownSeconds() <= 0L) "关闭" else formatSeconds(prefs.cooldownSeconds())

        val keywordsList = prefs.keywordsList()
        val keywords = when {
            keywordsList.isEmpty() -> "（空）"
            keywordsList.size <= 4 -> keywordsList.joinToString(separator = "，")
            else -> keywordsList.take(4).joinToString(separator = "，") + "…（${keywordsList.size}个）"
        }

        val sendersList = prefs.allowedSendersList()
        val senders = when {
            sendersList.isEmpty() -> "不限制"
            sendersList.size <= 2 -> sendersList.joinToString(separator = "，")
            else -> sendersList.take(2).joinToString(separator = "，") + "…（${sendersList.size}个）"
        }

        val excludeList = prefs.excludeKeywordsList()
        val exclude = when {
            excludeList.isEmpty() -> "无"
            excludeList.size <= 3 -> excludeList.joinToString(separator = "，")
            else -> excludeList.take(3).joinToString(separator = "，") + "…（${excludeList.size}个）"
        }

        val regexList = prefs.includeRegexList()
        val regex = when {
            regexList.isEmpty() -> "无"
            regexList.size == 1 -> regexList.first()
            else -> "${regexList.size}条"
        }

        val lastMatchText = prefs.lastMatch()?.let { match ->
            val bodyPreview = match.body
                .replace('\n', ' ')
                .trim()
                .let { text ->
                    if (text.length <= 24) text else text.take(24) + "…"
                }

            "${formatTime(match.receivedAtMillis)}  ${match.from}  $bodyPreview"
        } ?: "无"

        val statusTitle = when {
            !prefs.isEnabled() -> getString(R.string.status_title_paused)
            !smsGranted -> getString(R.string.status_title_need_sms)
            else -> getString(R.string.status_title_running)
        }

        val rulesSummary = "关键词 ${keywordsList.size} · 排除 ${excludeList.size} · 正则 ${regexList.size} · 指定号 ${sendersList.size}"
        val matchSummary = if (lastMatchText == "无") "最近命中：无" else "最近命中：$lastMatchText"

        binding.statusTitle.text = statusTitle
        binding.statusSubtitle.text = "$rulesSummary\n$matchSummary"

        val smsMeta = "短信:${if (smsGranted) getString(R.string.perm_granted) else getString(R.string.perm_not_granted)}"
        val notifMeta =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "通知:${if (notifGranted) getString(R.string.perm_granted) else getString(R.string.perm_not_granted)}"
            } else {
                "通知:${getString(R.string.perm_not_needed)}"
            }
        val ringMeta = "铃声:${if (ringtoneEnabled) getString(R.string.switch_on) else getString(R.string.switch_off)}"
        val alertMeta = "告警:$alertModeLabel"
        val cooldownMeta = "冷却:$cooldownLabel"
        binding.statusMeta.text = "$smsMeta · $notifMeta · $ringMeta\n$alertMeta · $cooldownMeta"

        val notifPermDetailLabel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (notifGranted) getString(R.string.perm_granted) else getString(R.string.perm_not_granted)
            } else {
                getString(R.string.perm_not_needed)
            }

        actionsAdapter.submit(
            buildHomeActions(
                smsGranted = smsGranted,
                notifGranted = notifGranted,
                ringtoneEnabled = ringtoneEnabled,
                ringtoneName = ringtoneName,
                lastMatchText = lastMatchText,
            ),
        )

        currentStatusDetails =
            "监控：${if (prefs.isEnabled()) "已启用" else "已关闭"}\n" +
                "短信权限：${if (smsGranted) "已授权" else "未授权"}\n" +
                "通知权限：$notifPermDetailLabel\n" +
                "告警方式：$alertModeLabel\n" +
                "铃声提醒：${if (ringtoneEnabled) getString(R.string.switch_on) else getString(R.string.switch_off)}\n" +
                "铃声：$ringtoneName\n" +
                "响铃自动停止：$autoStopLabel\n" +
                "重复告警冷却：$cooldownLabel\n" +
                "关键词：$keywords\n" +
                "指定号码：$senders\n" +
                "排除词：$exclude\n" +
                "正则：$regex\n" +
                "最近命中：$lastMatchText\n" +
                "版本：${BuildConfig.VERSION_NAME}"
    }

    private fun showStatusDetails() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.status_details_title)
            .setMessage(currentStatusDetails)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildHomeActions(
        smsGranted: Boolean,
        notifGranted: Boolean,
        ringtoneEnabled: Boolean,
        ringtoneName: String,
        lastMatchText: String,
    ): List<HomeActionItem> {
        val actions = buildList {
            add(
                HomeActionItem(
                    id = HomeActionId.STOP_RINGING,
                    title = getString(R.string.stop_ringing),
                    description = getString(R.string.home_stop_desc),
                    iconRes = R.drawable.ic_action_stop,
                    span = 2,
                    enabled = true,
                    showChevron = false,
                    tone = HomeActionTone.DANGER,
                ),
            )

            add(
                HomeActionItem(
                    id = HomeActionId.GRANT_SMS,
                    title = getString(R.string.home_sms_perm_title),
                    description =
                        if (smsGranted) {
                            getString(R.string.home_sms_perm_desc_granted)
                        } else {
                            getString(R.string.home_sms_perm_desc_grant)
                        },
                    iconRes = R.drawable.ic_action_sms,
                    enabled = !smsGranted,
                ),
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    HomeActionItem(
                        id = HomeActionId.GRANT_NOTIF,
                        title = getString(R.string.home_notif_perm_title),
                        description =
                            if (notifGranted) {
                                getString(R.string.home_notif_perm_desc_granted)
                            } else {
                                getString(R.string.home_notif_perm_desc_grant)
                            },
                        iconRes = R.drawable.ic_action_notifications,
                        enabled = !notifGranted,
                    ),
                )
            } else {
                add(
                    HomeActionItem(
                        id = HomeActionId.GRANT_NOTIF,
                        title = getString(R.string.home_notif_perm_title),
                        description = getString(R.string.home_notif_perm_desc_not_needed),
                        iconRes = R.drawable.ic_action_notifications,
                        enabled = false,
                        showChevron = false,
                    ),
                )
            }

            add(
                HomeActionItem(
                    id = HomeActionId.OPEN_RULES,
                    title = getString(R.string.home_rules_title),
                    description = getString(R.string.home_rules_desc),
                    iconRes = R.drawable.ic_action_settings,
                ),
            )

            add(
                HomeActionItem(
                    id = HomeActionId.CHOOSE_RINGTONE,
                    title = getString(R.string.home_ringtone_title),
                    description =
                        if (ringtoneEnabled) {
                            getString(R.string.home_ringtone_desc, ringtoneName)
                        } else {
                            getString(R.string.home_ringtone_desc_disabled, ringtoneName)
                        },
                    iconRes = R.drawable.ic_action_music,
                ),
            )

            add(
                HomeActionItem(
                    id = HomeActionId.TEST_ALERT,
                    title = getString(R.string.test_alert),
                    description = getString(R.string.home_test_desc),
                    iconRes = R.drawable.ic_action_test,
                ),
            )

            add(
                HomeActionItem(
                    id = HomeActionId.HISTORY,
                    title = getString(R.string.history_title),
                    description = getString(R.string.home_history_desc, lastMatchText),
                    iconRes = R.drawable.ic_action_history,
                ),
            )

            add(
                HomeActionItem(
                    id = HomeActionId.BATTERY,
                    title = getString(R.string.home_battery_title),
                    description = getString(R.string.home_battery_desc),
                    iconRes = R.drawable.ic_action_battery,
                ),
            )
        }

        return actions
    }

    private fun onActionClick(id: HomeActionId) {
        when (id) {
            HomeActionId.STOP_RINGING -> stopRinging()
            HomeActionId.GRANT_SMS -> {
                openSettingsAfterSmsGrant = true
                permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
            }

            HomeActionId.GRANT_NOTIF -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }

            HomeActionId.OPEN_RULES -> {
                openSettingsAfterSmsGrant = false
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            HomeActionId.CHOOSE_RINGTONE -> openRingtonePicker()
            HomeActionId.TEST_ALERT -> dispatchTestAlert()
            HomeActionId.HISTORY -> startActivity(Intent(this, HistoryActivity::class.java))
            HomeActionId.BATTERY -> openBatteryWhitelistSettings()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, prefs.continuousRingtoneUri())
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.choose_ringtone))
        }
        ringtonePickerLauncher.launch(intent)
    }

    private fun getRingtoneTitle(uri: Uri?): String {
        if (uri == null) return getString(R.string.ringtone_default)
        return runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ringtone_unknown)
    }

    private fun dispatchTestAlert() {
        val event = SmsEvent(
            from = "测试",
            body = "这是一条测试提醒（不会发送短信）。",
            receivedAtMillis = System.currentTimeMillis(),
            source = SmsSource.TEST,
        )
        AlertDispatcher.dispatch(this, prefs, event)
        prefs.setLastMatch(event)
        updateUi()
    }

    private fun stopRinging() {
        AlarmService.stop(applicationContext)
        TtsSpeaker.stop()
    }

    private fun openBatteryWhitelistSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun formatTime(millis: Long): String =
        runCatching {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrElse { "-" }

    private fun formatSeconds(seconds: Long): String =
        when {
            seconds < 60 -> "${seconds}秒"
            seconds % 60L == 0L -> "${seconds / 60L}分钟"
            else -> "${seconds}秒"
        }

    private fun getParcelableUriExtra(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
}
