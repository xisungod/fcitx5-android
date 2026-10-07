/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference

/** Foreground permission UI for the first visible keyboard; it never reads an SMS itself. */
class SmsCodePermissionActivity : Activity() {
    private var requestStarted = false
    private var permissionDenied = false
    private var settingsEnablePending = false
    private var leftForSettings = false
    private var denialDialog: AlertDialog? = null

    @Keep
    private val preferenceListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (!enabled) settingsEnablePending = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStarted = savedInstanceState?.getBoolean(STATE_REQUEST_STARTED) == true
        permissionDenied = savedInstanceState?.getBoolean(STATE_PERMISSION_DENIED) == true
        settingsEnablePending = savedInstanceState?.getBoolean(STATE_SETTINGS_ENABLE_PENDING) == true
        leftForSettings = savedInstanceState?.getBoolean(STATE_LEFT_FOR_SETTINGS) == true
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.registerOnChangeListener(preferenceListener)
        if (settingsEnablePending) return
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            SmsCodeAccess.finishAuthorization(this, true)
            finish()
        } else if (permissionDenied) {
            showPermissionDenied()
        } else if (!AppPrefs.getInstance().clipboard.verificationCodeFromSms.getValue()) {
            finish()
        } else if (!requestStarted) {
            requestStarted = true
            SmsCodeAccess.noteExplicitAuthorizationAttempt()
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SMS) return
        val granted = permissions.indexOf(Manifest.permission.RECEIVE_SMS).takeIf { it >= 0 }
            ?.let { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED } == true
        SmsCodeAccess.finishAuthorization(this, granted)
        if (granted) {
            finish()
        } else {
            permissionDenied = true
            showPermissionDenied()
        }
    }

    private fun showPermissionDenied() {
        denialDialog?.dismiss()
        denialDialog = AlertDialog.Builder(this)
            .setTitle(R.string.verification_code_sms_permission_denied_title)
            .setMessage(R.string.verification_code_sms_permission_denied_message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.verification_code_sms_open_app_settings) { _, _ ->
                settingsEnablePending = true
                leftForSettings = false
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onPause() {
        if (settingsEnablePending) leftForSettings = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (leftForSettings) {
            val continueEnable = settingsEnablePending
            settingsEnablePending = false
            if (continueEnable) SmsCodeAccess.completeSettingsEnable(this)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_REQUEST_STARTED, requestStarted)
        outState.putBoolean(STATE_PERMISSION_DENIED, permissionDenied)
        outState.putBoolean(STATE_SETTINGS_ENABLE_PENDING, settingsEnablePending)
        outState.putBoolean(STATE_LEFT_FOR_SETTINGS, leftForSettings)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        denialDialog?.dismiss()
        denialDialog = null
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.unregisterOnChangeListener(preferenceListener)
        super.onDestroy()
    }

    companion object {
        internal const val REQUEST_SMS = 47
        private const val STATE_REQUEST_STARTED = "sms_request_started"
        private const val STATE_PERMISSION_DENIED = "sms_permission_denied"
        private const val STATE_SETTINGS_ENABLE_PENDING = "sms_settings_enable_pending"
        private const val STATE_LEFT_FOR_SETTINGS = "sms_left_for_settings"
    }
}
