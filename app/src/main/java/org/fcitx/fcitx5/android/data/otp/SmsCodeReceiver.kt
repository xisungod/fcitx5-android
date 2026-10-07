/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.otp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Protected manifest receiver, enabled only while SMS code reading and SMS permission allow it. */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        SmsCodeStatus.recordReceived()
        if (!SmsCodeAccess.canReceive(context)) {
            SmsCodeStatus.recordBlocked()
            return
        }
        val body = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.takeIf { messages -> messages.isNotEmpty() && messages.none { it == null } }
                ?.joinToString("") { it?.messageBody.orEmpty() }
        }.getOrNull()
        if (body == null) {
            SmsCodeStatus.recordMalformed()
            return
        }
        val code = VerificationCodes.extract(body)
        SmsCodeStatus.recordParseResult(code != null)
        if (code == null) return
        VerificationCodes.publish(code, VerificationCodes.Source.Sms)
    }
}
