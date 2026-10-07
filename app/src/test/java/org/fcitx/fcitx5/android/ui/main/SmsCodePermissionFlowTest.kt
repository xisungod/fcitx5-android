/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.otp.SmsCodeAccess
import org.fcitx.fcitx5.android.data.otp.SmsCodeReceiver
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SmsCodePermissionFlowTest {
    private lateinit var application: Application
    private lateinit var activity: MainActivity
    private lateinit var stored: SharedPreferences
    private var previousApplication: Any? = null
    private var previousPrefs: Any? = null

    @Before fun prepare() {
        application = RuntimeEnvironment.getApplication()
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        shadowOf(application).denyPermissions(Manifest.permission.RECEIVE_SMS)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousApplication = get(null)
            set(null, app)
        }
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            previousPrefs = get(null)
            set(null, null)
        }
        stored = application.getSharedPreferences("sms-code-permission-flow-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        // Exercise the Activity's actual permission callbacks without creating its native engine
        // or input service, neither of which should be required to enable SMS reception.
        activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_AXiang)
    }

    @After fun clean() {
        dialog()?.dismiss()
        val listener = AppPrefs::class.java.getDeclaredField("onSharedPreferenceChangeListener")
            .apply { isAccessible = true }.get(AppPrefs.getInstance()) as SharedPreferences.OnSharedPreferenceChangeListener
        stored.unregisterOnSharedPreferenceChangeListener(listener)
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousPrefs)
        }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
    }

    private fun pref() = AppPrefs.getInstance().clipboard.verificationCodeFromSms
    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)
    private fun pending() = ReflectionHelpers.getField<Boolean>(activity, "smsSettingsEnablePending")
    private fun dialog() = ReflectionHelpers.getField<AlertDialog?>(activity, "smsPermissionDeniedDialog")
    private fun result(granted: Boolean) = ReflectionHelpers.callInstanceMethod<Void>(activity,
        "onSmsPermissionResult", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, granted))
    private fun resumePermissionState() = ReflectionHelpers.callInstanceMethod<Void>(activity, "refreshSmsPermissionState")
    private fun receiverEnabled(): Boolean = application.packageManager.getComponentEnabledSetting(
        android.content.ComponentName(application, SmsCodeReceiver::class.java)) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    @Test fun denialRetainsEnabledIntentAndDirectSystemGrantStartsReceptionOnResume() {
        pref().setValue(true)
        result(false)
        assertTrue(pref().getValue())
        assertFalse(receiverEnabled())
        assertTrue(dialog()?.isShowing == true)
        assertNotNull(dialog()?.getButton(AlertDialog.BUTTON_POSITIVE))
        assertFalse(pending())
        grant()
        resumePermissionState()
        assertTrue(pref().getValue())
        assertTrue(receiverEnabled())
        assertFalse("Direct system authorization does not require our settings action", pending())
    }

    @Test fun explicitSettingsActionCompletesTheEnableAttemptOnGrant() {
        pref().setValue(true)
        result(false)
        dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pending())
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${application.packageName}", intent.data.toString())
        grant()
        resumePermissionState()
        assertTrue(pref().getValue())
        assertTrue(receiverEnabled())
        assertFalse(pending())
    }

    @Test fun returningFromSettingsWithoutGrantDoesNotKeepAStaleEnableIntent() {
        pref().setValue(true)
        result(false)
        dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        resumePermissionState()
        assertTrue(pref().getValue())
        assertFalse(receiverEnabled())
        assertFalse(pending())
        grant()
        resumePermissionState()
        assertTrue(pref().getValue())
        assertTrue(receiverEnabled())
    }

    @Test fun permissionGrantReplaysTheEnabledPreferenceOnceWithoutStartingAnIme() {
        pref().setValue(true)
        shadowOf(Looper.getMainLooper()).idle()
        grant()
        var changes = 0
        val listener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
            if (enabled) changes++
        }
        pref().registerOnChangeListener(listener)
        try {
            result(true)
            assertEquals(1, changes)
            assertTrue(receiverEnabled())
            assertNull(dialog())
        } finally {
            pref().unregisterOnChangeListener(listener)
        }
    }

    @Test fun optingOutBeforeAPendingGrantCannotEnableReception() {
        pref().setValue(true)
        pref().setValue(false)
        grant()
        result(true)
        assertFalse(pref().getValue())
        assertFalse(receiverEnabled())
    }

    @Test fun explicitSwitchOffCancelsAPendingSettingsEnableAttempt() {
        pref().setValue(true)
        ReflectionHelpers.setField(activity, "smsSettingsEnablePending", true)
        val listener = ReflectionHelpers.getField<ManagedPreference.OnChangeListener<Boolean>>(
            activity, "smsCodePrefListener")
        pref().registerOnChangeListener(listener)
        try {
            pref().setValue(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(pending())
            grant()
            resumePermissionState()
            assertFalse(pref().getValue())
            assertFalse(receiverEnabled())
        } finally {
            pref().unregisterOnChangeListener(listener)
        }
    }

    @Test fun settingsGrantReplaysAnAlreadyEnabledPreferenceWithoutDuplicateNotifications() {
        pref().setValue(true)
        shadowOf(Looper.getMainLooper()).idle()
        grant()
        ReflectionHelpers.setField(activity, "smsSettingsEnablePending", true)
        var changes = 0
        val listener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> changes++ }
        pref().registerOnChangeListener(listener)
        try {
            resumePermissionState()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, changes)
            assertTrue(receiverEnabled())
            assertFalse(pending())
        } finally {
            pref().unregisterOnChangeListener(listener)
        }
    }
}
