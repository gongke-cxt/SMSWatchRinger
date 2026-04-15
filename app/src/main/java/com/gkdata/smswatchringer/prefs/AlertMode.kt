package com.gkdata.smswatchringer.prefs

enum class AlertMode(val value: String) {
    NOTIFICATION("notification"),
    CONTINUOUS_RINGTONE("continuous_ringtone"),
    ;

    companion object {
        fun fromValue(value: String?): AlertMode =
            entries.firstOrNull { it.value == value } ?: CONTINUOUS_RINGTONE
    }
}
