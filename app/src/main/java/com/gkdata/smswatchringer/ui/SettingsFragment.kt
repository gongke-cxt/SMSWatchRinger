package com.gkdata.smswatchringer.ui

import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.gkdata.smswatchringer.R
import com.gkdata.smswatchringer.alert.NotificationHelper
import com.gkdata.smswatchringer.device.PhoneNumberResolver
import com.gkdata.smswatchringer.prefs.Prefs
import com.gkdata.smswatchringer.push.SmsCallbackClient
import com.gkdata.smswatchringer.sms.model.SmsEvent
import com.gkdata.smswatchringer.sms.model.SmsSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.telephony.SubscriptionManager

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: Prefs

    private var ringtonePref: Preference? = null
    private var callbackUrlPref: EditTextPreference? = null
    private var callbackImeiPref: EditTextPreference? = null
    private var callbackSim1Pref: EditTextPreference? = null
    private var callbackSim2Pref: EditTextPreference? = null

    private val sharedPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.KEY_CALLBACK_URL,
                Prefs.KEY_CALLBACK_IMEI,
                Prefs.KEY_CALLBACK_PHONE_NUMBER_SIM1,
                Prefs.KEY_CALLBACK_PHONE_NUMBER_SIM2,
                    -> updateCallbackSummaries()
            }
        }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult

            val pickedUri = result.data?.let { intent ->
                getParcelableUriExtra(intent, RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            prefs.setContinuousRingtoneUri(pickedUri)
            NotificationHelper.ensureChannels(requireContext(), pickedUri)
            updateRingtoneSummary()
        }

    private val phonePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            updateCallbackSummaries()

            val grantedAny = result.values.any { it }
            if (!grantedAny) {
                showPhonePermissionSettingsDialog()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        prefs = Prefs(requireContext())

        val keywordsPref = findPreference<EditTextPreference>(Prefs.KEY_KEYWORDS)
        configureMultilineEditText(keywordsPref)

        val sendersPref = findPreference<EditTextPreference>(Prefs.KEY_ALLOWED_SENDERS)
        configureMultilineEditText(sendersPref)

        val excludePref = findPreference<EditTextPreference>(Prefs.KEY_EXCLUDE_KEYWORDS)
        configureMultilineEditText(excludePref)

        val regexPref = findPreference<EditTextPreference>(Prefs.KEY_INCLUDE_REGEX)
        configureMultilineEditText(regexPref)

        callbackUrlPref = findPreference(Prefs.KEY_CALLBACK_URL)
        callbackImeiPref = findPreference(Prefs.KEY_CALLBACK_IMEI)
        callbackSim1Pref = findPreference(Prefs.KEY_CALLBACK_PHONE_NUMBER_SIM1)
        callbackSim2Pref = findPreference(Prefs.KEY_CALLBACK_PHONE_NUMBER_SIM2)

        ringtonePref = findPreference(Prefs.KEY_CONTINUOUS_RINGTONE)
        ringtonePref?.setOnPreferenceClickListener {
            openRingtonePicker()
            true
        }
        updateRingtoneSummary()

        updateCallbackSummaries()

        findPreference<Preference>("tts_engine_settings")?.setOnPreferenceClickListener {
            openTtsEngineSettings()
            true
        }

        findPreference<Preference>("tts_help")?.setOnPreferenceClickListener {
            showTtsHelp()
            true
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference.key) {
            Prefs.KEY_CALLBACK_URL -> showCallbackUrlDialog(preference as EditTextPreference)
            Prefs.KEY_CALLBACK_IMEI -> showDeviceIdDialog(preference as EditTextPreference)
            Prefs.KEY_CALLBACK_PHONE_NUMBER_SIM1 -> showSimNumberDialog(slotIndex = 0, preference = preference as EditTextPreference)
            Prefs.KEY_CALLBACK_PHONE_NUMBER_SIM2 -> showSimNumberDialog(slotIndex = 1, preference = preference as EditTextPreference)
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
        updateCallbackSummaries()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
        super.onPause()
    }

    private fun configureMultilineEditText(pref: EditTextPreference?) {
        pref?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = false
            editText.minLines = 4
            editText.gravity = Gravity.TOP or Gravity.START
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }

    private fun updateRingtoneSummary() {
        val title = getRingtoneTitle(prefs.continuousRingtoneUri())
        ringtonePref?.summary = "${getString(R.string.choose_ringtone_summary)}\n当前：$title"
    }

    private fun updateCallbackSummaries() {
        callbackUrlPref?.summary =
            callbackUrlPref
                ?.text
                ?.trim()
                .orEmpty()
                .ifBlank { getString(R.string.callback_copy_empty) }

        callbackImeiPref?.summary =
            prefs.callbackImei()
                .ifBlank { getString(R.string.callback_copy_empty) }

        callbackSim1Pref?.summary =
            resolvePhoneSummary(slotIndex = 0, configured = callbackSim1Pref?.text.orEmpty())

        callbackSim2Pref?.summary =
            resolvePhoneSummary(slotIndex = 1, configured = callbackSim2Pref?.text.orEmpty())
    }

    private fun resolvePhoneSummary(slotIndex: Int, configured: String): String {
        val configuredValue = configured.trim()
        if (configuredValue.isNotBlank()) return configuredValue
        if (!PhoneNumberResolver.hasAnyPermission(requireContext())) return getString(R.string.callback_phone_permission_needed)

        val detected = resolveDetectedNumberForSlot(slotIndex)
        if (detected.isNotBlank()) return detected

        return getString(R.string.callback_refresh_sim_not_found)
    }

    private fun resolveDetectedNumberForSlot(slotIndex: Int): String =
        PhoneNumberResolver.getSimNumbers(requireContext())
            .firstOrNull { it.simSlotIndex == slotIndex }
            ?.number
            ?.trim()
            .orEmpty()

    private fun ensurePhonePermissions(): Boolean {
        if (PhoneNumberResolver.hasAnyPermission(requireContext())) return true

        val permissions = PhoneNumberResolver.requiredPermissions()
        val needsRequest =
            permissions.any { perm ->
                ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED
            }
        if (needsRequest) {
            phonePermissionsLauncher.launch(permissions.toTypedArray())
            return false
        }
        return true
    }

    private fun showCallbackUrlDialog(preference: EditTextPreference) {
        val editText =
            EditText(requireContext()).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(preference.text.orEmpty())
                setSelection(text.length)
            }

        val container = FrameLayout(requireContext()).apply {
            val padding = dp(20)
            setPadding(padding, padding, padding, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.callback_test_connectivity, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                preference.text = editText.text?.toString()?.trim().orEmpty()
                updateCallbackSummaries()
                dialog.dismiss()
            }

            val testButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
            val originalText = testButton.text
            testButton.setOnClickListener {
                val endpoint = editText.text?.toString()?.trim().orEmpty()
                if (endpoint.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.callback_test_empty_url), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                testButton.isEnabled = false
                testButton.text = getString(R.string.callback_test_in_progress)

                val subId =
                    SubscriptionManager.getDefaultSmsSubscriptionId()
                        .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                val testEvent = SmsEvent(
                    from = "TEST",
                    body = "测试连通性",
                    receivedAtMillis = System.currentTimeMillis(),
                    source = SmsSource.TEST,
                    subscriptionId = subId,
                )

                SmsCallbackClient.probeAsync(endpoint, prefs, testEvent) { result ->
                    requireActivity().runOnUiThread {
                        testButton.isEnabled = true
                        testButton.text = originalText

                        val message =
                            when {
                                result.ok && result.code != null -> getString(R.string.callback_test_ok, result.code)
                                result.code != null -> getString(R.string.callback_test_fail_http, result.code)
                                else -> getString(
                                    R.string.callback_test_fail_error,
                                    result.message.orEmpty().ifBlank { "unknown" },
                                )
                            }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showDeviceIdDialog(preference: EditTextPreference) {
        val editText =
            EditText(requireContext()).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                setText(preference.text.orEmpty())
                setSelection(text.length)
            }

        val container = FrameLayout(requireContext()).apply {
            val padding = dp(20)
            setPadding(padding, padding, padding, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.action_fetch, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                preference.text = editText.text?.toString()?.trim().orEmpty()
                updateCallbackSummaries()
                dialog.dismiss()
            }

            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val androidId =
                    runCatching {
                        Settings.Secure.getString(
                            requireContext().contentResolver,
                            Settings.Secure.ANDROID_ID,
                        )
                    }.getOrNull().orEmpty()

                if (androidId.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.callback_copy_empty), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                editText.setText(androidId)
                editText.setSelection(editText.text.length)
            }
        }

        dialog.show()
    }

    private fun showSimNumberDialog(slotIndex: Int, preference: EditTextPreference) {
        val editText =
            EditText(requireContext()).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_PHONE
                setText(preference.text.orEmpty())
                setSelection(text.length)
            }

        val container = FrameLayout(requireContext()).apply {
            val padding = dp(20)
            setPadding(padding, padding, padding, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.action_fetch, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                preference.text = editText.text?.toString()?.trim().orEmpty()
                updateCallbackSummaries()
                dialog.dismiss()
            }

            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                if (!ensurePhonePermissions()) return@setOnClickListener

                val detected = resolveDetectedNumberForSlot(slotIndex).trim()
                if (detected.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.callback_refresh_sim_not_found), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                editText.setText(detected)
                editText.setSelection(editText.text.length)
            }
        }

        dialog.show()
    }

    private fun showPhonePermissionSettingsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.callback_phone_permission_settings_title)
            .setMessage(R.string.callback_phone_permission_settings_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                openAppSettings()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${requireContext().packageName}"),
        )
        startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        return runCatching { RingtoneManager.getRingtone(requireContext(), uri)?.getTitle(requireContext()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ringtone_unknown)
    }

    private fun openTtsEngineSettings() {
        val intent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.Settings\$TextToSpeechSettingsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = requireContext().packageManager
        if (intent.resolveActivity(pm) != null) {
            runCatching { startActivity(intent) }
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tts_engine_settings_title)
            .setMessage(R.string.tts_settings_not_found_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showTtsHelp() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tts_help_title)
            .setMessage(R.string.tts_help_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.tts_engine_settings_title) { _, _ ->
                openTtsEngineSettings()
            }
            .show()
    }

    private fun getParcelableUriExtra(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
}
