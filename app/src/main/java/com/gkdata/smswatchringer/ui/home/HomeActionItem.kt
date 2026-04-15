package com.gkdata.smswatchringer.ui.home

import androidx.annotation.DrawableRes

enum class HomeActionId {
    STOP_RINGING,
    GRANT_SMS,
    GRANT_NOTIF,
    OPEN_RULES,
    CHOOSE_RINGTONE,
    TEST_ALERT,
    HISTORY,
    BATTERY,
}

enum class HomeActionTone {
    NORMAL,
    DANGER,
}

data class HomeActionItem(
    val id: HomeActionId,
    val title: String,
    val description: String,
    @DrawableRes val iconRes: Int,
    val span: Int = 1,
    val enabled: Boolean = true,
    val showChevron: Boolean = true,
    val tone: HomeActionTone = HomeActionTone.NORMAL,
)

