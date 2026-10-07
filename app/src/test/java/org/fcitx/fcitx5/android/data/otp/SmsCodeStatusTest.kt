/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Telephony
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SmsCodeStatusTest {
    private lateinit var application: Application
    private lateinit var stored: SharedPreferences
    private lateinit var component: ComponentName
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
        stored = application.getSharedPreferences("sms-code-status-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        component = ComponentName(application, SmsCodeReceiver::class.java)
        application.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
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

    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)

    // Synthetic SMS-DELIVER, UCS-2, invented sender and verification code only.
    private fun sms(body: String): Intent {
        val text = body.toByteArray(Charsets.UTF_16BE)
        val pdu = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 4, 5, 0x81.toByte(), 0x01, 0x00, 0xf0.toByte(), 0, 8))
            write(byteArrayOf(0x62, 0x01, 0x70, 0x31, 0x02, 0, 0))
            write(text.size)
            write(text)
        }.toByteArray()
        return Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("format", "3gpp").putExtra("pdus", arrayOf(pdu))
    }

    @Test fun snapshotsAreImmutableAndResetOnlyClearsProcessCounters() {
        SmsCodeStatus.recordReceived()
        SmsCodeStatus.recordParseResult(true)
        val before = SmsCodeStatus.snapshot()
        SmsCodeStatus.recordPrepared()
        assertEquals(SmsCodeStatus.Counters(received = 1, matched = 1), before)
        assertEquals(1, SmsCodeStatus.snapshot().prepared)
        val preferences = stored.all
        SmsCodeStatus.reset()
        assertEquals(SmsCodeStatus.Counters(), SmsCodeStatus.snapshot())
        assertEquals(preferences, stored.all)
    }

    @Test fun unrelatedBroadcastDoesNotClaimASmsWasReceived() {
        grant()
        SmsCodeReceiver().onReceive(application,
            sms("验证码 482913").setAction("unrelated.action"))
        assertEquals(SmsCodeStatus.Counters(), SmsCodeStatus.snapshot())
        assertNull(VerificationCodes.fresh())
    }

    @Test fun deliveryAfterOptOutIsCountedButNeverParsed() {
        grant()
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(false)
        SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
        assertEquals(SmsCodeStatus.Counters(received = 1, blocked = 1), SmsCodeStatus.snapshot())
        assertNull(VerificationCodes.fresh())
    }

    @Test fun missingPermissionIsDistinguishedFromParseFailure() {
        SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
        assertEquals(SmsCodeStatus.Counters(received = 1, blocked = 1), SmsCodeStatus.snapshot())
        assertFalse(SmsCodeStatus.snapshot(application).permissionGranted)
        assertNull(VerificationCodes.fresh())
    }

    @Test fun missingOrInvalidPdusAreCountedWithoutPublishing() {
        grant()
        SmsCodeReceiver().onReceive(application, Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        SmsCodeReceiver().onReceive(application, Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("format", "3gpp").putExtra("pdus", arrayOf(byteArrayOf(1))))
        assertEquals(SmsCodeStatus.Counters(received = 2, malformed = 2), SmsCodeStatus.snapshot())
        assertNull(VerificationCodes.fresh())
    }

    @Test fun ordinarySmsIsCountedAsUnmatchedNotMalformed() {
        grant()
        SmsCodeReceiver().onReceive(application, sms("订单 482913 已发货"))
        assertEquals(SmsCodeStatus.Counters(received = 1, unmatched = 1), SmsCodeStatus.snapshot())
        assertNull(VerificationCodes.fresh())
    }

    @Test fun matchingSmsPublishesOnceButDoesNotClaimItWasPreparedOrRendered() {
        grant()
        val published = mutableListOf<VerificationCodes.Code>()
        val listener = VerificationCodes.Listener { published += it }
        VerificationCodes.addListener(listener)
        try {
            SmsCodeReceiver().onReceive(application, sms("验证码 482913，5分钟内有效"))
            assertEquals(1, published.size)
            assertEquals("482913", published.single().code)
            assertEquals(VerificationCodes.Source.Sms, published.single().source)
            assertEquals(SmsCodeStatus.Counters(received = 1, matched = 1), SmsCodeStatus.snapshot())
        } finally {
            VerificationCodes.removeListener(listener)
        }
    }

    @Test fun readingHealthDoesNotEnableReceiverOrChangeTheFeaturePreference() {
        grant()
        val before = stored.all
        val health = SmsCodeStatus.snapshot(application)
        assertTrue(health.userUnlocked)
        assertEquals(true, health.featureEnabled)
        assertTrue(health.permissionGranted)
        assertFalse(health.receiverEnabled)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            application.packageManager.getComponentEnabledSetting(component))
        assertEquals(before, stored.all)
        SmsCodeAccess.sync(application)
        assertTrue(SmsCodeStatus.snapshot(application).receiverEnabled)
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(false)
        SmsCodeAccess.sync(application)
        assertFalse(SmsCodeStatus.snapshot(application).receiverEnabled)
    }

    @Test fun readingHealthDoesNotRepairAnInvalidPreferenceOrTriggerReceiverSync() {
        grant()
        val pref = AppPrefs.getInstance().clipboard.verificationCodeFromSms
        // Before adding a feature listener, simulate a corrupt value in existing storage.
        stored.edit().putString(pref.key, "not-a-boolean").commit()
        var changes = 0
        val listener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
            changes++
            SmsCodeAccess.sync(application)
        }
        pref.registerOnChangeListener(listener)
        try {
            val before = stored.all
            val health = SmsCodeStatus.snapshot(application)
            assertNull(health.featureEnabled)
            assertTrue(health.permissionGranted)
            assertFalse(health.receiverEnabled)
            assertEquals("not-a-boolean", stored.getString(pref.key, null))
            assertEquals(before, stored.all)
            assertEquals(0, changes)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                application.packageManager.getComponentEnabledSetting(component))
        } finally {
            pref.unregisterOnChangeListener(listener)
        }
    }

    @Test fun appOpsStatusAndRuntimeGrantRemainSeparateAndReadingDoesNotChangeEither() {
        grant()
        val manager = application.getSystemService(AppOpsManager::class.java)
        val states = listOf(
            AppOpsManager.MODE_ALLOWED to SmsCodeStatus.ReceiveSmsAppOp.ALLOWED,
            AppOpsManager.MODE_IGNORED to SmsCodeStatus.ReceiveSmsAppOp.IGNORED,
            AppOpsManager.MODE_ERRORED to SmsCodeStatus.ReceiveSmsAppOp.ERRORED,
            AppOpsManager.MODE_DEFAULT to SmsCodeStatus.ReceiveSmsAppOp.DEFAULT,
            AppOpsManager.MODE_FOREGROUND to SmsCodeStatus.ReceiveSmsAppOp.FOREGROUND
        )
        for ((mode, expected) in states) {
            // Only the test shadow sets a mode; production reads never request or mutate it.
            shadowOf(manager).setMode(AppOpsManager.OPSTR_RECEIVE_SMS,
                application.applicationInfo.uid, application.packageName, mode)
            val health = SmsCodeStatus.snapshot(application)
            assertTrue(health.permissionGranted)
            assertEquals(expected, health.appOp)
            assertEquals(mode, manager.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_RECEIVE_SMS,
                application.applicationInfo.uid, application.packageName))
        }
    }

    @Test fun lockedUserHealthNeverReadsCredentialPreferences() {
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val field = AppPrefs::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val prefs = field.get(null)
        field.set(null, null)
        try {
            val health = SmsCodeStatus.snapshot(application)
            assertFalse(health.userUnlocked)
            assertNull(health.featureEnabled)
            SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
            assertEquals(SmsCodeStatus.Counters(received = 1, blocked = 1), SmsCodeStatus.snapshot())
        } finally {
            field.set(null, prefs)
            shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        }
    }

    @Test fun statusCannotRetainASenderBodyOrVerificationCode() {
        grant()
        SmsCodeReceiver().onReceive(application, sms("验证码 482913，5分钟内有效"))
        val fields = SmsCodeStatus.Counters::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(fields.isNotEmpty())
        assertTrue(fields.all { it.type == Int::class.javaPrimitiveType })
        val summary = SmsCodeStatus.snapshot(application).toString()
        assertFalse(summary.contains("482913"))
        assertFalse(summary.contains("验证码"))
        assertFalse(summary.contains("10000"))
    }

    @Test fun countersDoNotLoseConcurrentReceiverAndUiUpdates() {
        val workers = List(4) {
            Thread {
                repeat(100) {
                    SmsCodeStatus.recordReceived()
                    SmsCodeStatus.recordParseResult(true)
                    SmsCodeStatus.recordPrepared()
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        assertEquals(SmsCodeStatus.Counters(received = 400, matched = 400, prepared = 400),
            SmsCodeStatus.snapshot())
    }
}
