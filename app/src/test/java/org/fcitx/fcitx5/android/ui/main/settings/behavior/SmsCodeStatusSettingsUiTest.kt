/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Looper
import android.os.UserManager
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.otp.SmsCodeReceiver
import org.fcitx.fcitx5.android.data.otp.SmsCodeStatus
import org.fcitx.fcitx5.android.data.otp.VerificationCodes
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.clipboardManager as androidClipboardManager
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SmsCodeStatusSettingsUiTest {
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
        stored = application.getSharedPreferences("sms-code-status-ui-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        application.packageManager.setComponentEnabledSetting(
            ComponentName(application, SmsCodeReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        SmsCodeStatus.reset()
        VerificationCodes.consume()
    }

    @After fun clean() {
        SmsCodeStatus.reset()
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

    private fun fixture(block: (AppCompatActivity, ClipboardSettingsFragment) -> Unit) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_FcitxAppTheme)
        val activity = controller.setup().get()
        val host = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(host)
        val fragment = ClipboardSettingsFragment()
        activity.supportFragmentManager.beginTransaction().add(host.id, fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        try {
            block(activity, fragment)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun preference(fragment: ClipboardSettingsFragment): Preference =
        fragment.findPreference<Preference>(ClipboardSettingsFragment.SMS_STATUS_KEY)!!

    private fun openStatus(fragment: ClipboardSettingsFragment): AlertDialog {
        preference(fragment).performClick()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test fun statusEntryRemainsUsableWhenSmsPermissionAndReceiverAreUnavailable() = fixture { activity, fragment ->
        val entry = preference(fragment)
        assertTrue(entry.isEnabled)
        assertFalse(entry.isPersistent)
        assertEquals(activity.getString(R.string.sms_code_status_title), entry.title)
        assertTrue(entry.summary.toString().contains(activity.getString(R.string.sms_code_state_on)))
        assertTrue(entry.summary.toString().contains(activity.getString(R.string.sms_code_state_not_allowed)))
        assertTrue(entry.summary.toString().contains(activity.getString(R.string.sms_code_state_disabled)))
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(false)
        fragment.refreshSmsStatusSummary()
        assertTrue(entry.summary.toString().contains(activity.getString(R.string.sms_code_state_off)))
        assertTrue(entry.isEnabled)
    }

    @Test fun copyingStatusReadsFreshCountersWithoutCopyingOrConsumingTheCode() = fixture { activity, fragment ->
        VerificationCodes.publish("975310", VerificationCodes.Source.Sms)
        SmsCodeStatus.recordReceived()
        val before = stored.all.toMap()
        val dialog = openStatus(fragment)
        val visible = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(visible.contains(BuildConfig.VERSION_NAME))
        assertFalse(visible.contains("975310"))
        SmsCodeStatus.recordReceived()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val copied = activity.androidClipboardManager.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains("收到通知：2"))
        assertFalse(copied.contains("975310"))
        assertEquals("975310", VerificationCodes.fresh()?.code)
        assertEquals(before, stored.all)
        assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS))
        assertFalse(SmsCodeStatus.snapshot(activity).receiverEnabled)
    }

    @Test fun clearingCountersPreservesTheCurrentCodeAndAvailability() = fixture { _, fragment ->
        VerificationCodes.publish("975310", VerificationCodes.Source.Sms)
        SmsCodeStatus.recordReceived()
        SmsCodeStatus.recordParseResult(true)
        SmsCodeStatus.recordPrepared()
        val before = stored.all.toMap()
        val healthBefore = SmsCodeStatus.snapshot(application)
        openStatus(fragment).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SmsCodeStatus.Counters(), SmsCodeStatus.snapshot())
        assertEquals("975310", VerificationCodes.fresh()?.code)
        assertEquals(before, stored.all)
        assertEquals(healthBefore.copy(counters = SmsCodeStatus.Counters()), SmsCodeStatus.snapshot(application))
        assertTrue(preference(fragment).summary.toString().contains("收到 0 条通知"))
    }

    @Test fun foregroundOnlyAppOpsIsShownSeparatelyFromTheRuntimeGrant() = fixture { activity, fragment ->
        shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val manager = application.getSystemService(AppOpsManager::class.java)
        shadowOf(manager).setMode(AppOpsManager.OPSTR_RECEIVE_SMS,
            application.applicationInfo.uid, application.packageName, AppOpsManager.MODE_FOREGROUND)
        val details = fragment.renderSmsStatus(activity)
        assertTrue(details.contains(activity.getString(R.string.sms_code_state_foreground)))
        assertEquals(PackageManager.PERMISSION_GRANTED, activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS))
        assertEquals(AppOpsManager.MODE_FOREGROUND, manager.unsafeCheckOpRawNoThrow(
            AppOpsManager.OPSTR_RECEIVE_SMS, application.applicationInfo.uid, application.packageName))
    }

    @Test fun runningViewRefreshesSummaryWhenCountersChange() = fixture { _, fragment ->
        val before = preference(fragment).summary.toString()
        SmsCodeStatus.recordReceived()
        shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS)
        assertNotEquals(before, preference(fragment).summary.toString())
        assertTrue(preference(fragment).summary.toString().contains("收到 1 条通知"))
    }

    @Test fun unknownFeatureStateDoesNotRepairAMalformedPreference() = fixture { activity, fragment ->
        // Deliberately bypass the preference's repairing getter: the diagnostic snapshot must
        // be read-only even for a bad stored type. No SMS preference listeners are registered.
        stored.edit().putString("verification_code_sms", "malformed").commit()
        val before = stored.all.toMap()
        val rendered = fragment.renderSmsStatus(activity)
        assertTrue(rendered.contains(activity.getString(R.string.sms_code_state_unknown)))
        assertEquals(before, stored.all)
        assertFalse(SmsCodeStatus.snapshot(activity).receiverEnabled)
    }
}
