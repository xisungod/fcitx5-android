/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.otp.SmsCodeAccess
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import splitties.views.topPadding

class MainActivity : AppCompatActivity() {

    /** Default-on authorization is requested once at user entry; a manual ON can retry it. */
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onSmsPermissionResult(granted)
    }

    private var smsPermissionRequestPending = false
    private var smsSettingsEnablePending = false
    private var smsPermissionDeniedDialog: AlertDialog? = null

    private val smsCodePrefListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (enabled) requestSmsPermissionIfNeeded() else smsSettingsEnablePending = false
        SmsCodeAccess.sync(this)
    }

    private fun requestSmsPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED &&
            !smsPermissionRequestPending) {
            smsPermissionRequestPending = true
            SmsCodeAccess.noteExplicitAuthorizationAttempt()
            smsPermission.launch(Manifest.permission.RECEIVE_SMS)
        } else {
            SmsCodeAccess.sync(this)
        }
    }

    private fun onSmsPermissionResult(granted: Boolean) {
        smsPermissionRequestPending = false
        SmsCodeAccess.finishAuthorization(this, granted)
        if (!granted) {
            if (!isFinishing && !isDestroyed) {
                smsPermissionDeniedDialog?.dismiss()
                smsPermissionDeniedDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.verification_code_sms_permission_denied_title)
                    .setMessage(R.string.verification_code_sms_permission_denied_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.verification_code_sms_open_app_settings) { _, _ ->
                        // Continue this explicit enable attempt only on returning from settings.
                        smsSettingsEnablePending = true
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    }
                    .show()
            }
        }
        SmsCodeAccess.sync(this)
    }

    private fun refreshSmsPermissionState() {
        if (smsSettingsEnablePending) {
            smsSettingsEnablePending = false
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                SmsCodeAccess.completeSettingsEnable(this)
            }
        }
        SmsCodeAccess.sync(this)
    }

    override fun onResume() {
        super.onResume()
        refreshSmsPermissionState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SMS_PERMISSION_REQUEST_PENDING, smsPermissionRequestPending)
        outState.putBoolean(STATE_SMS_SETTINGS_ENABLE_PENDING, smsSettingsEnablePending)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        smsPermissionDeniedDialog?.dismiss()
        smsPermissionDeniedDialog = null
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.unregisterOnChangeListener(smsCodePrefListener)
        super.onDestroy()
    }

    private val viewModel: MainViewModel by viewModels()

    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        smsPermissionRequestPending = savedInstanceState?.getBoolean(STATE_SMS_PERMISSION_REQUEST_PENDING) ?: false
        smsSettingsEnablePending = savedInstanceState?.getBoolean(STATE_SMS_SETTINGS_ENABLE_PENDING) ?: false
        val requestAutomaticSmsPermission = !smsPermissionRequestPending &&
            SmsCodeAccess.beginAutomaticAuthorization(this)
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.let {
            it.registerOnChangeListener(smsCodePrefListener)
            if (requestAutomaticSmsPermission) requestSmsPermissionIfNeeded()
        }
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            windowInsets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // always show toolbar back arrow icon
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = SettingsRoute.createGraph(navController)
        viewModel.toolbarTitle.observe(this) {
            supportActionBar!!.title = it
        }
        viewModel.toolbarShadow.observe(this) {
            binding.toolbar.elevation = 0f
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            supportActionBar?.setDisplayHomeAsUpEnabled(!dest.hasRoute<SettingsRoute.Index>())
            dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            if (dest.hasRoute<SettingsRoute.Theme>()) {
                viewModel.disableToolbarShadow()
            } else {
                viewModel.enableToolbarShadow()
            }
        }
        processIntent(intent)
        checkNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        // "minimize" the activity if we can't go back
        return navController.navigateUp() || super.onSupportNavigateUp() || moveTaskToBack(false)
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }
            Intent.ACTION_VIEW -> intent.data?.let {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pinyin_dict)
                    .setMessage(R.string.whether_import_dict)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        navController.popBackStack(SettingsRoute.Index, false)
                        navController.navigateWithAnim(SettingsRoute.PinyinDict(it))
                    }
                    .show()
            }
            Intent.ACTION_RUN -> {
                val route = intent.parcelable<SettingsRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(SettingsRoute.Index, false)
                navController.navigateWithAnim(route)
            }
        }
    }

    private var needNotifications by AppPrefs.getInstance().internal.needNotifications

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                needNotifications = true
                return
            }
            // do not ask again if user denied the request
            if (!needNotifications) return
            // always show a dialog to explain why we need notification permission,
            // regardless of `shouldShowRequestPermissionRationale(...)`
            AlertDialog.Builder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(R.string.i_do_not_need_it) { _, _ ->
                    // do not ask again if user denied the request
                    needNotifications = false
                }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 0) return
        // do not ask again if user denied the request
        needNotifications = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
    }

    override fun onStop() {
        viewModel.fcitx.runIfReady {
            save()
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
        private const val STATE_SMS_PERMISSION_REQUEST_PENDING = "sms_permission_request_pending"
        private const val STATE_SMS_SETTINGS_ENABLE_PENDING = "sms_settings_enable_pending"
    }

}
