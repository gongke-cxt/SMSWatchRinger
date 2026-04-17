package com.gkdata.smswatchringer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var prefs: Prefs

    private var ringtonePref: Preference? = null
    private var callbackCopyImeiPref: Preference? = null
    private var callbackCopyPhonePref: Preference? = null
    private var callbackDetectedNumbersPref: Preference? = null

    private val sharedPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.KEY_CALLBACK_IMEI,
                Prefs.KEY_CALLBACK_PHONE_NUMBER,
                    -> updateCallbackCopySummaries()
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
            updateCallbackCopySummaries()
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

        findPreference<EditTextPreference>(Prefs.KEY_CALLBACK_URL)?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        findPreference<EditTextPreference>(Prefs.KEY_CALLBACK_IMEI)?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_TEXT
        }
        findPreference<EditTextPreference>(Prefs.KEY_CALLBACK_PHONE_NUMBER)?.setOnBindEditTextListener { editText ->
            editText.isSingleLine = true
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }

        ringtonePref = findPreference(Prefs.KEY_CONTINUOUS_RINGTONE)
        ringtonePref?.setOnPreferenceClickListener {
            openRingtonePicker()
            true
        }
        updateRingtoneSummary()

        callbackCopyImeiPref = findPreference("callback_copy_imei")
        callbackCopyPhonePref = findPreference("callback_copy_phone")
        callbackDetectedNumbersPref = findPreference("callback_detected_numbers")

        callbackCopyImeiPref?.setOnPreferenceClickListener {
            copyToClipboard(
                label = getString(R.string.callback_copy_imei_title),
                text = prefs.callbackImei(),
            )
            true
        }
        callbackCopyPhonePref?.setOnPreferenceClickListener {
            if (!PhoneNumberResolver.hasAnyPermission(requireContext())) {
                val permissions = PhoneNumberResolver.requiredPermissions()
                val needsRequest =
                    permissions.any { perm ->
                        ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED
                    }
                if (needsRequest) {
                    phonePermissionsLauncher.launch(permissions.toTypedArray())
                    return@setOnPreferenceClickListener true
                }
            }

            copyToClipboard(
                label = getString(R.string.callback_copy_phone_title),
                text = prefs.callbackPhoneNumber(),
            )
            true
        }

        callbackDetectedNumbersPref?.setOnPreferenceClickListener {
            if (!PhoneNumberResolver.hasAnyPermission(requireContext())) {
                val permissions = PhoneNumberResolver.requiredPermissions()
                val needsRequest =
                    permissions.any { perm ->
                        ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED
                    }
                if (needsRequest) {
                    phonePermissionsLauncher.launch(permissions.toTypedArray())
                    return@setOnPreferenceClickListener true
                }
            }

            showDetectedPhoneNumbersDialog()
            true
        }
        updateCallbackCopySummaries()

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
        updateCallbackCopySummaries()
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

    private fun updateCallbackCopySummaries() {
        callbackCopyImeiPref?.summary =
            prefs.callbackImei()
                .ifBlank { getString(R.string.callback_copy_empty) }

        val phoneNumber = prefs.callbackPhoneNumber()
        callbackCopyPhonePref?.summary =
            when {
                phoneNumber.isNotBlank() -> phoneNumber
                !PhoneNumberResolver.hasAnyPermission(requireContext()) -> getString(R.string.callback_phone_permission_needed)
                else -> getString(R.string.callback_copy_empty)
            }

        val detectedNumbers = PhoneNumberResolver.getSimNumbers(requireContext())
        callbackDetectedNumbersPref?.summary =
            when {
                !PhoneNumberResolver.hasAnyPermission(requireContext()) -> getString(R.string.callback_phone_permission_needed)
                detectedNumbers.isEmpty() -> getString(R.string.callback_detected_numbers_empty)
                detectedNumbers.size == 1 -> detectedNumbers.first().number
                else -> "${detectedNumbers.size} 个号码：${detectedNumbers.joinToString(separator = " / ") { it.number }}"
            }
    }

    private fun showDetectedPhoneNumbersDialog() {
        val detected = PhoneNumberResolver.getSimNumbers(requireContext())
        if (detected.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.callback_detected_numbers_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val items =
            detected.map { sim ->
                val slotText = "SIM${sim.simSlotIndex + 1}"
                val carrierText = sim.carrierName?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "$slotText$carrierText：${sim.number}"
            }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.callback_detected_numbers_title)
            .setItems(items) { _, which ->
                val sim = detected.getOrNull(which) ?: return@setItems
                copyToClipboard(
                    label = "SIM${sim.simSlotIndex + 1}",
                    text = sim.number,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.callback_copy_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
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
