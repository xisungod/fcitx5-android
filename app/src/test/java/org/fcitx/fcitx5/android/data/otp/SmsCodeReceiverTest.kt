/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.otp

import android.Manifest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SmsCodeReceiverTest {
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
        stored = application.getSharedPreferences("sms-code-receiver-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        component = ComponentName(application, SmsCodeReceiver::class.java)
        application.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
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

    private fun optIn() = AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(true)
    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.RECEIVE_SMS)
    private fun receiverAvailable(): Boolean = application.packageManager.queryBroadcastReceivers(
        Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).setPackage(application.packageName), 0
    ).any { it.activityInfo.name == SmsCodeReceiver::class.java.name }

    // Entirely synthetic SMS-DELIVER, UCS-2, sender 10000; no real messages or device data.
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

    @Test fun defaultOnPreferenceStillRequiresSmsPermissionAndAProtectedReceiver() {
        val info = application.packageManager.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(info.enabled)
        assertTrue(info.exported)
        assertFalse(info.directBootAware)
        assertEquals("android.permission.BROADCAST_SMS", info.permission)
        assertFalse(receiverAvailable())
        assertTrue(AppPrefs.getInstance().clipboard.verificationCodeFromSms.getValue())
    }

    @Test fun grantingSmsPermissionDoesNotOptTheUserIn() {
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(false)
        grant()
        assertFalse(SmsCodeAccess.sync(application))
        assertFalse(receiverAvailable())
        SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
        assertNull(VerificationCodes.fresh())
    }

    @Test fun optingInWithoutPermissionCannotEnableTheReceiver() {
        optIn()
        assertFalse(SmsCodeAccess.sync(application))
        assertFalse(receiverAvailable())
        SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
        assertNull(VerificationCodes.fresh())
    }

    @Test fun permissionGrantEnablesDeliveryWithoutStartingAnInputMethod() {
        // Robolectric also lists manifest receivers here. Enabling the component must not add
        // a second dynamic registration; SMS delivery must work without creating an IME.
        val registrationsBefore = shadowOf(application).getRegisteredReceivers().count {
            it.broadcastReceiver is SmsCodeReceiver
        }
        optIn()
        assertFalse(SmsCodeAccess.sync(application))
        grant()
        assertTrue(SmsCodeAccess.sync(application))
        assertTrue(receiverAvailable())
        assertEquals(registrationsBefore, shadowOf(application).getRegisteredReceivers().count {
            it.broadcastReceiver is SmsCodeReceiver
        })
        val intent = sms("验证码 482913，5分钟内有效")
        assertEquals("验证码 482913，5分钟内有效",
            Telephony.Sms.Intents.getMessagesFromIntent(intent).single().messageBody)
        SmsCodeReceiver().onReceive(application, intent)
        assertEquals("482913", VerificationCodes.fresh()?.code)
        assertEquals(VerificationCodes.Source.Sms, VerificationCodes.fresh()?.source)
    }

    @Test fun receiverUsesOnePublicationForOneValidSms() {
        optIn()
        grant()
        SmsCodeAccess.sync(application)
        val received = mutableListOf<String>()
        val listener = VerificationCodes.Listener { received += it.code }
        VerificationCodes.addListener(listener)
        try {
            SmsCodeReceiver().onReceive(application, sms("验证码 8361"))
            assertEquals(listOf("8361"), received)
        } finally {
            VerificationCodes.removeListener(listener)
        }
    }

    @Test fun optingOutDisablesDeliveryAndClearsThePreviouslyReceivedSms() {
        optIn()
        grant()
        SmsCodeAccess.sync(application)
        SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
        assertNotNull(VerificationCodes.fresh())
        AppPrefs.getInstance().clipboard.verificationCodeFromSms.setValue(false)
        assertFalse(SmsCodeAccess.sync(application))
        assertFalse(receiverAvailable())
        assertNull(VerificationCodes.fresh())
        // Simulate a broadcast already queued before the component was disabled.
        SmsCodeReceiver().onReceive(application, sms("验证码 8361"))
        assertNull(VerificationCodes.fresh())
    }

    @Test fun permissionRevocationDropsQueuedDeliveryBeforeAnyComponentRefresh() {
        optIn()
        grant()
        SmsCodeAccess.sync(application)
        shadowOf(application).denyPermissions(Manifest.permission.RECEIVE_SMS)
        SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
        assertNull(VerificationCodes.fresh())
        assertFalse(SmsCodeAccess.sync(application))
        assertFalse(receiverAvailable())
    }

    @Test fun lockedDeviceDoesNotReadCredentialPreferencesOrParseSms() {
        optIn()
        grant()
        SmsCodeAccess.sync(application)
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val prefs = AppPrefs::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val current = prefs.get(null)
        prefs.set(null, null)
        try {
            assertFalse(SmsCodeAccess.canReceive(application))
            assertFalse(SmsCodeAccess.sync(application))
            SmsCodeReceiver().onReceive(application, sms("验证码 482913"))
            assertNull(VerificationCodes.fresh())
        } finally {
            prefs.set(null, current)
            shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        }
    }

    @Test fun nonSmsMalformedPdusAndOrdinaryNotificationsNeverPublishCodes() {
        optIn()
        grant()
        SmsCodeAccess.sync(application)
        SmsCodeReceiver().onReceive(application, sms("验证码 482913").setAction("unrelated.action"))
        SmsCodeReceiver().onReceive(application, Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        SmsCodeReceiver().onReceive(application, sms("订单 482913 已发货"))
        assertNull(VerificationCodes.fresh())
    }

    @Test fun disablingSmsDoesNotDeleteACopiedVerificationCode() {
        VerificationCodes.publish("8361", VerificationCodes.Source.Clipboard)
        SmsCodeAccess.sync(application)
        assertEquals(VerificationCodes.Source.Clipboard, VerificationCodes.fresh()?.source)
    }
}
