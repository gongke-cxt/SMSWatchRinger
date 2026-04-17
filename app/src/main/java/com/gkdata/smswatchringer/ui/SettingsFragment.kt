package com.gkdata.smswatchringer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.gkdata.smswatchringer.R
import com.gkdata.smswatchringer.alert.NotificationHelper
import com.gkdata.smswatchringer.device.PhoneNumberResolver
import com.gkdata.smswatchringer.prefs.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            updateCallbackSummaries()
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

        callbackUrlPref?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        callbackImeiPref?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_TEXT
        }
        callbackSim1Pref?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_PHONE

            if (callbackSim1Pref?.text.isNullOrBlank()) {
                val detected = resolveDetectedNumberForSlot(slotIndex = 0)
                if (detected.isNotBlank()) editText.setText(detected)
            }
        }
        callbackSim2Pref?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_PHONE

            if (callbackSim2Pref?.text.isNullOrBlank()) {
                val detected = resolveDetectedNumberForSlot(slotIndex = 1)
                if (detected.isNotBlank()) editText.setText(detected)
            }
        }

        callbackSim1Pref?.setOnPreferenceClickListener {
            ensurePhonePermissions()
        }
        callbackSim2Pref?.setOnPreferenceClickListener {
            ensurePhonePermissions()
        }

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
        if (PhoneNumberResolver.hasAnyPermission(requireContext())) return false

        val permissions = PhoneNumberResolver.requiredPermissions()
        val needsRequest =
            permissions.any { perm ->
                ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED
            }
        if (needsRequest) {
            phonePermissionsLauncher.launch(permissions.toTypedArray())
            return true
        }
        return false
    }

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
