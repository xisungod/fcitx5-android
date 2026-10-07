/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.otp.SmsCodeStatus
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.utils.clipboardManager as androidClipboardManager

class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {
    private var smsStatusPreference: Preference? = null
    private var smsStatusDialog: AlertDialog? = null

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        smsStatusPreference = Preference(screen.context).apply {
            key = SMS_STATUS_KEY
            setTitle(R.string.sms_code_status_title)
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                showSmsStatus()
                true
            }
        }.also { screen.addPreference(it) }
        refreshSmsStatusSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        smsStatusPreference = findPreference(SMS_STATUS_KEY)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshSmsStatusSummary()
                    delay(1000)
                }
            }
        }
    }

    internal fun refreshSmsStatusSummary() {
        val ctx = context ?: return
        val health = SmsCodeStatus.snapshot(ctx)
        smsStatusPreference?.summary = ctx.getString(R.string.sms_code_status_summary,
            featureState(ctx, health.featureEnabled),
            ctx.getString(if (health.permissionGranted) R.string.sms_code_state_allowed else R.string.sms_code_state_not_allowed),
            ctx.getString(if (health.receiverEnabled) R.string.sms_code_state_enabled else R.string.sms_code_state_disabled),
            health.counters.received, health.counters.matched, health.counters.prepared)
    }

    private fun showSmsStatus() {
        val ctx = requireContext()
        smsStatusDialog?.dismiss()
        smsStatusDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.sms_code_status_title)
            .setMessage(renderSmsStatus(ctx))
            .setPositiveButton(R.string.sms_code_status_copy) { _, _ ->
                // Re-read counters and availability at the click, not when the dialog opened.
                ctx.androidClipboardManager.setPrimaryClip(ClipData.newPlainText(
                    ctx.getString(R.string.sms_code_status_title), renderSmsStatus(ctx)))
            }
            .setNeutralButton(R.string.sms_code_status_clear) { _, _ ->
                SmsCodeStatus.reset()
                refreshSmsStatusSummary()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    internal fun renderSmsStatus(ctx: Context): String {
        val health = SmsCodeStatus.snapshot(ctx)
        val appOp = when (health.appOp) {
            SmsCodeStatus.ReceiveSmsAppOp.ALLOWED -> R.string.sms_code_state_allowed
            SmsCodeStatus.ReceiveSmsAppOp.IGNORED -> R.string.sms_code_state_not_allowed
            SmsCodeStatus.ReceiveSmsAppOp.ERRORED -> R.string.sms_code_state_errored
            SmsCodeStatus.ReceiveSmsAppOp.DEFAULT -> R.string.sms_code_state_system_default
            SmsCodeStatus.ReceiveSmsAppOp.FOREGROUND -> R.string.sms_code_state_foreground
            SmsCodeStatus.ReceiveSmsAppOp.UNKNOWN -> R.string.sms_code_state_unknown
        }
        return ctx.getString(R.string.sms_code_status_details,
            BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            ctx.applicationInfo.targetSdkVersion,
            featureState(ctx, health.featureEnabled),
            ctx.getString(if (health.permissionGranted) R.string.sms_code_state_allowed else R.string.sms_code_state_not_allowed),
            ctx.getString(if (health.receiverEnabled) R.string.sms_code_state_enabled else R.string.sms_code_state_disabled),
            ctx.getString(appOp), health.counters.received, health.counters.matched,
            health.counters.prepared, health.counters.blocked, health.counters.malformed,
            health.counters.unmatched) + "\n\n" + ctx.getString(R.string.sms_code_status_explanation)
    }

    private fun featureState(ctx: Context, enabled: Boolean?): String = ctx.getString(when (enabled) {
        true -> R.string.sms_code_state_on
        false -> R.string.sms_code_state_off
        null -> R.string.sms_code_state_unknown
    })

    override fun onDestroyView() {
        smsStatusDialog?.dismiss()
        smsStatusDialog = null
        smsStatusPreference = null
        super.onDestroyView()
    }

    companion object {
        internal const val SMS_STATUS_KEY = "sms_code_status"
    }
}
