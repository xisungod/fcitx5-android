/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.otp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Registered by the input method only while the user has enabled it and granted RECEIVE_SMS. */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val body = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.joinToString("") { it?.messageBody.orEmpty() }
        }.getOrNull() ?: return
        val code = VerificationCodes.extract(body) ?: return
        VerificationCodes.publish(code, VerificationCodes.Source.Sms)
    }
}
