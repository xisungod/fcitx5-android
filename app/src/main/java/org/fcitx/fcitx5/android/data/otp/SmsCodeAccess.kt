/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber

/** Controls the incoming-SMS receiver without depending on the input method's lifetime. */
object SmsCodeAccess {
    private fun isUserUnlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /** Check again on delivery: a queued broadcast must not outlive opt-out or permission revocation. */
    fun canReceive(context: Context): Boolean =
        isUserUnlocked(context) &&
            AppPrefs.getInstance().clipboard.verificationCodeFromSms.getValue() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED

    /** Apply this version's requested default once, only at a foreground user entry. */
    fun prepareUserEntry(context: Context) {
        if (!isUserUnlocked(context)) return
        val prefs = AppPrefs.getInstance()
        if (!prefs.internal.smsCodeOnboardingApplied.getValue()) {
            // Old versions wrote false on any permission denial. Apply the new default once,
            // then preserve every later explicit OFF instead of undoing it on each visit.
            prefs.clipboard.verificationCodeFromSms.sharedPreferences.edit(commit = true) {
                putBoolean(prefs.internal.smsCodeOnboardingApplied.key, true)
                putBoolean(prefs.clipboard.verificationCodeFromSms.key, true)
            }
        }
        sync(context)
    }

    /** Reserve the single automatic foreground authorization attempt before showing UI. */
    fun beginAutomaticAuthorization(context: Context): Boolean {
        if (!isUserUnlocked(context)) return false
        prepareUserEntry(context)
        val prefs = AppPrefs.getInstance()
        if (!prefs.clipboard.verificationCodeFromSms.getValue() ||
            prefs.internal.smsCodeAutomaticAuthorizationAttempted.getValue()) return false
        prefs.internal.smsCodeAutomaticAuthorizationAttempted.sharedPreferences.edit(commit = true) {
            putBoolean(prefs.internal.smsCodeAutomaticAuthorizationAttempted.key, true)
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** Manual ON is a new explicit attempt; it must not restart automatic prompts later. */
    fun noteExplicitAuthorizationAttempt() {
        val prefs = AppPrefs.getInstance()
        prefs.internal.smsCodeAutomaticAuthorizationAttempted.sharedPreferences.edit(commit = true) {
            putBoolean(prefs.internal.smsCodeOnboardingApplied.key, true)
            putBoolean(prefs.internal.smsCodeAutomaticAuthorizationAttempted.key, true)
        }
    }

    /** This is called only after the service has rechecked that its input view is visible. */
    fun requestAuthorizationForVisibleKeyboard(context: Context, visible: Boolean): Boolean {
        if (!visible || !beginAutomaticAuthorization(context)) return false
        context.startActivity(Intent(context, SmsCodePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun finishAuthorization(context: Context, granted: Boolean) {
        val pref = AppPrefs.getInstance().clipboard.verificationCodeFromSms
        if (!granted) pref.setValue(false)
        else if (pref.getValue()) pref.fireChange()
        sync(context)
    }

    /** Called only when the user explicitly chose the app-settings action after denial. */
    fun completeSettingsEnable(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) !=
            PackageManager.PERMISSION_GRANTED) return false
        val pref = AppPrefs.getInstance().clipboard.verificationCodeFromSms
        val wasEnabled = pref.getValue()
        pref.setValue(true)
        if (wasEnabled) pref.fireChange()
        sync(context)
        return true
    }

    /** The manifest stays disabled until both the preference and system SMS permission allow it. */
    fun sync(context: Context): Boolean {
        // Credential preferences cannot be inspected before unlock. Keep the stored component
        // state for the next normal startup; the receiver itself is not direct-boot aware.
        if (!isUserUnlocked(context)) return false
        val wanted = canReceive(context)
        if (!wanted && VerificationCodes.latest?.source == VerificationCodes.Source.Sms) {
            VerificationCodes.consume()
        }
        val component = ComponentName(context, SmsCodeReceiver::class.java)
        val state = if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        return runCatching {
            val manager = context.packageManager
            if (manager.getComponentEnabledSetting(component) != state) {
                manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
            wanted
        }.onFailure {
            // Never log message content, sender or the code.
            Timber.w(it, "Could not update SMS verification receiver availability")
        }.getOrDefault(false)
    }
}
