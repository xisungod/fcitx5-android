/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * Process-local receive health, containing counts and availability only. This object never
 * retains SMS content, senders, verification codes, timestamps or a persistent event trace.
 */
object SmsCodeStatus {
    data class Counters(
        val received: Int = 0,
        val matched: Int = 0,
        val prepared: Int = 0,
        val blocked: Int = 0,
        val malformed: Int = 0,
        val unmatched: Int = 0
    )

    enum class ReceiveSmsAppOp { ALLOWED, IGNORED, ERRORED, DEFAULT, FOREGROUND, UNKNOWN }

    data class Health(
        val counters: Counters,
        val userUnlocked: Boolean,
        val featureEnabled: Boolean?,
        val permissionGranted: Boolean,
        val receiverEnabled: Boolean,
        val appOp: ReceiveSmsAppOp
    )

    private var counters = Counters()

    @Synchronized fun recordReceived() {
        counters = counters.copy(received = counters.received + 1)
    }

    @Synchronized fun recordParseResult(matched: Boolean) {
        counters = if (matched) counters.copy(matched = counters.matched + 1)
            else counters.copy(unmatched = counters.unmatched + 1)
    }

    @Synchronized fun recordBlocked() {
        counters = counters.copy(blocked = counters.blocked + 1)
    }

    @Synchronized fun recordMalformed() {
        counters = counters.copy(malformed = counters.malformed + 1)
    }

    /** A prepared suggestion is not proof that the keyboard has rendered it. */
    @Synchronized fun recordPrepared() {
        counters = counters.copy(prepared = counters.prepared + 1)
    }

    @Synchronized fun snapshot(): Counters = counters

    @Synchronized fun reset() {
        counters = Counters()
    }

    /** Read availability without changing preferences, permissions, AppOps or component state. */
    fun snapshot(context: Context): Health {
        val unlocked = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        val enabled = if (unlocked) runCatching {
            val pref = AppPrefs.getInstance().clipboard.verificationCodeFromSms
            // ManagedPreference.getValue repairs invalid types by writing its default.
            // Health inspection must remain read-only, even for a corrupt preference.
            pref.sharedPreferences.getBoolean(pref.key, pref.defaultValue)
        }.getOrNull() else null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val receiverEnabled = runCatching {
            val component = ComponentName(context, SmsCodeReceiver::class.java)
            when (context.packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
                    context.packageManager.getReceiverInfo(
                        component, PackageManager.MATCH_DISABLED_COMPONENTS
                    ).enabled
                else -> false
            }
        }.getOrDefault(false)
        return Health(snapshot(), unlocked, enabled, granted, receiverEnabled, receiveSmsAppOp(context))
    }

    private fun receiveSmsAppOp(context: Context): ReceiveSmsAppOp = runCatching {
        val manager = context.getSystemService(AppOpsManager::class.java)
            ?: return@runCatching ReceiveSmsAppOp.UNKNOWN
        val op = AppOpsManager.permissionToOp(Manifest.permission.RECEIVE_SMS)
            ?: return@runCatching ReceiveSmsAppOp.UNKNOWN
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The ordinary check maps FOREGROUND to ALLOWED. Keep the raw distinction so
            // a status screen does not present a conditional grant as unrestricted access.
            manager.unsafeCheckOpRawNoThrow(op, context.applicationInfo.uid, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            manager.checkOpNoThrow(op, context.applicationInfo.uid, context.packageName)
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> ReceiveSmsAppOp.ALLOWED
            AppOpsManager.MODE_IGNORED -> ReceiveSmsAppOp.IGNORED
            AppOpsManager.MODE_ERRORED -> ReceiveSmsAppOp.ERRORED
            AppOpsManager.MODE_DEFAULT -> ReceiveSmsAppOp.DEFAULT
            AppOpsManager.MODE_FOREGROUND -> ReceiveSmsAppOp.FOREGROUND
            else -> ReceiveSmsAppOp.UNKNOWN
        }
    }.getOrDefault(ReceiveSmsAppOp.UNKNOWN)
}
