/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SmsCodeOnboardingTest {
    private lateinit var application: Application
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
        stored = application.getSharedPreferences("sms-code-onboarding-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        VerificationCodes.consume()
    }

    @After fun clean() {
        VerificationCodes.consume()
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

    private fun prefs() = AppPrefs.getInstance()
    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)

    @Test fun defaultOnStillDoesNotRequestPermissionOrEnableReceptionInTheBackground() {
        assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(SmsCodeAccess.sync(application))
        assertFalse(prefs().internal.smsCodeOnboardingApplied.getValue())
        assertFalse(prefs().internal.smsCodeAutomaticAuthorizationAttempted.getValue())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun oldSilentDenialIsMigratedAtUserEntryOnceAndLaterOffIsPreserved() {
        prefs().clipboard.verificationCodeFromSms.setValue(false)
        SmsCodeAccess.sync(application)
        assertFalse(prefs().clipboard.verificationCodeFromSms.getValue())
        SmsCodeAccess.prepareUserEntry(application)
        assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
        assertTrue(prefs().internal.smsCodeOnboardingApplied.getValue())
        prefs().clipboard.verificationCodeFromSms.setValue(false)
        SmsCodeAccess.prepareUserEntry(application)
        assertFalse(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
    }

    @Test fun automaticAuthorizationIsReservedOnceAcrossRepeatedEntries() {
        assertTrue(SmsCodeAccess.beginAutomaticAuthorization(application))
        assertTrue(prefs().internal.smsCodeAutomaticAuthorizationAttempted.getValue())
        assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
        SmsCodeAccess.finishAuthorization(application, false)
        assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(SmsCodeAccess.canReceive(application))
        assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
    }

    @Test fun denialThenDirectSystemGrantEnablesReceptionOnVisibleEntryWithoutAnotherPrompt() {
        assertTrue(SmsCodeAccess.beginAutomaticAuthorization(application))
        SmsCodeAccess.finishAuthorization(application, false)
        assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(SmsCodeAccess.canReceive(application))
        grant()
        assertFalse(SmsCodeAccess.requestAuthorizationForVisibleKeyboard(application, visible = true))
        assertTrue(SmsCodeAccess.canReceive(application))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            application.packageManager.getComponentEnabledSetting(
                android.content.ComponentName(application, SmsCodeReceiver::class.java)))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun hiddenKeyboardDoesNotMigrateOrLaunchAnything() {
        prefs().clipboard.verificationCodeFromSms.setValue(false)
        assertFalse(SmsCodeAccess.requestAuthorizationForVisibleKeyboard(application, visible = false))
        assertFalse(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(prefs().internal.smsCodeOnboardingApplied.getValue())
        assertFalse(prefs().internal.smsCodeAutomaticAuthorizationAttempted.getValue())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun firstVisibleKeyboardLaunchesExactlyOneProtectedPermissionActivity() {
        assertTrue(SmsCodeAccess.requestAuthorizationForVisibleKeyboard(application, visible = true))
        val intent = shadowOf(application).nextStartedActivity
        assertEquals(SmsCodePermissionActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val info = application.packageManager.getActivityInfo(intent.component!!, 0)
        assertFalse(info.exported)
        assertFalse(SmsCodeAccess.requestAuthorizationForVisibleKeyboard(application, visible = true))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun existingPermissionNeedsNoPromptAndRevokingItDoesNotRestartAutomaticPrompts() {
        grant()
        assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
        assertTrue(prefs().internal.smsCodeAutomaticAuthorizationAttempted.getValue())
        shadowOf(application).denyPermissions(Manifest.permission.RECEIVE_SMS)
        assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
    }

    @Test fun explicitManualRetryDoesNotClearTheAutomaticPromptGuard() {
        SmsCodeAccess.beginAutomaticAuthorization(application)
        SmsCodeAccess.finishAuthorization(application, false)
        prefs().clipboard.verificationCodeFromSms.setValue(false)
        prefs().clipboard.verificationCodeFromSms.setValue(true)
        SmsCodeAccess.noteExplicitAuthorizationAttempt()
        assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
    }

    @Test fun lockedVisibleEntryDoesNotReadPreferencesOrLaunchARequest() {
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val field = AppPrefs::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val current = field.get(null)
        field.set(null, null)
        try {
            assertFalse(SmsCodeAccess.requestAuthorizationForVisibleKeyboard(application, visible = true))
            assertNull(shadowOf(application).nextStartedActivity)
        } finally {
            field.set(null, current)
            shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        }
    }

    @Test fun permissionActivityRequestsOnlyIncomingSmsAndGrantEnablesTheReceiver() {
        SmsCodeAccess.beginAutomaticAuthorization(application)
        val controller = Robolectric.buildActivity(SmsCodePermissionActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertArrayEquals(arrayOf(Manifest.permission.RECEIVE_SMS), shadowOf(activity).lastRequestedPermission.requestedPermissions)
            grant()
            activity.onRequestPermissionsResult(SmsCodePermissionActivity.REQUEST_SMS,
                arrayOf(Manifest.permission.RECEIVE_SMS), intArrayOf(PackageManager.PERMISSION_GRANTED))
            assertTrue(activity.isFinishing)
            assertTrue(SmsCodeAccess.canReceive(application))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun denialRecreationShowsTheExplanationWithoutRequestingAgain() {
        SmsCodeAccess.beginAutomaticAuthorization(application)
        val controller = Robolectric.buildActivity(SmsCodePermissionActivity::class.java).setup()
        val activity = controller.get()
        activity.onRequestPermissionsResult(SmsCodePermissionActivity.REQUEST_SMS,
            arrayOf(Manifest.permission.RECEIVE_SMS), intArrayOf(PackageManager.PERMISSION_DENIED))
        assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
        assertFalse(SmsCodeAccess.canReceive(application))
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        val recreated = Robolectric.buildActivity(SmsCodePermissionActivity::class.java).create(saved).start().resume()
        try {
            assertNull(shadowOf(recreated.get()).lastRequestedPermission)
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
            assertFalse(SmsCodeAccess.beginAutomaticAuthorization(application))
        } finally {
            recreated.pause().stop().destroy()
        }
    }

    @Test fun permissionActivitySettingsActionCompletesOnlyTheExplicitAttempt() {
        SmsCodeAccess.beginAutomaticAuthorization(application)
        val controller = Robolectric.buildActivity(SmsCodePermissionActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onRequestPermissionsResult(SmsCodePermissionActivity.REQUEST_SMS,
                arrayOf(Manifest.permission.RECEIVE_SMS), intArrayOf(PackageManager.PERMISSION_DENIED))
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
            controller.pause()
            grant()
            controller.resume()
            assertTrue(prefs().clipboard.verificationCodeFromSms.getValue())
            assertTrue(SmsCodeAccess.canReceive(application))
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun cancellingTheSettingsEnableIntentStillClosesThePermissionActivityOnReturn() {
        SmsCodeAccess.beginAutomaticAuthorization(application)
        val controller = Robolectric.buildActivity(SmsCodePermissionActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.onRequestPermissionsResult(SmsCodePermissionActivity.REQUEST_SMS,
                arrayOf(Manifest.permission.RECEIVE_SMS), intArrayOf(PackageManager.PERMISSION_DENIED))
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause()
            // Reproduce explicit OFF while this activity is behind system settings. Denial
            // retained the enabled intent, so only this user action cancels the continuation.
            prefs().clipboard.verificationCodeFromSms.setValue(false)
            shadowOf(Looper.getMainLooper()).idle()
            grant()
            controller.resume()
            assertTrue(activity.isFinishing)
            assertFalse(prefs().clipboard.verificationCodeFromSms.getValue())
            assertFalse(SmsCodeAccess.canReceive(application))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
