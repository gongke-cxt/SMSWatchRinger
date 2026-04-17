package com.gkdata.smswatchringer.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object PhoneNumberResolver {

    data class SimNumber(
        val subscriptionId: Int,
        val simSlotIndex: Int,
        val number: String,
        val carrierName: String?,
    )

    fun requiredPermissions(): List<String> =
        buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }

    fun hasAnyPermission(context: Context): Boolean =
        requiredPermissions().any { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    fun getSimNumbers(context: Context): List<SimNumber> {
        if (!hasAnyPermission(context)) return emptyList()

        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val subscriptions =
            runCatching { subscriptionManager.activeSubscriptionInfoList }
                .getOrNull()
                .orEmpty()

        return subscriptions
            .mapNotNull { info ->
                val subscriptionId = info.subscriptionId
                val slotIndex = info.simSlotIndex
                val carrierName = info.carrierName?.toString()

                val numberFromSubInfo = info.number?.trim().orEmpty()
                val numberFromTelephony =
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            telephonyManager.createForSubscriptionId(subscriptionId).line1Number?.trim().orEmpty()
                        } else {
                            telephonyManager.line1Number?.trim().orEmpty()
                        }
                    }.getOrNull().orEmpty()

                val number = when {
                    numberFromSubInfo.isNotBlank() -> numberFromSubInfo
                    numberFromTelephony.isNotBlank() -> numberFromTelephony
                    else -> ""
                }
                if (number.isBlank()) return@mapNotNull null

                SimNumber(
                    subscriptionId = subscriptionId,
                    simSlotIndex = slotIndex,
                    number = number,
                    carrierName = carrierName,
                )
            }
            .distinctBy { it.number }
    }

    fun resolveBestNumber(context: Context, preferredSubscriptionId: Int? = null): String {
        val candidates = getSimNumbers(context)
        if (candidates.isEmpty()) return ""

        preferredSubscriptionId
            ?.let { subId -> candidates.firstOrNull { it.subscriptionId == subId }?.number }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val defaultSmsSubId =
            runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
                .getOrNull()
                ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

        defaultSmsSubId
            ?.let { subId -> candidates.firstOrNull { it.subscriptionId == subId }?.number }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return candidates.first().number
    }
}

