package com.gkdata.smswatchringer.sms.model

data class SmsEvent(
    val from: String,
    val body: String,
    val receivedAtMillis: Long,
    val source: SmsSource,
)

