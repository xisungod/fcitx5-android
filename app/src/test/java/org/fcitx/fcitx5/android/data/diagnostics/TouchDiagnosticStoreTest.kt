/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.diagnostics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.json.JSONObject
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchDiagnosticStoreTest {
    private lateinit var store: TouchDiagnosticStore
    private var previousApplication: Any? = null
    private var previousPrefs: Any? = null
    private lateinit var stored: SharedPreferences

    @Before fun prepare() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
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
        stored = application.getSharedPreferences("touch-diagnostic-store-test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        AppPrefs.init(stored)
        store = TouchDiagnosticStore(application)
        store.clear()
    }

    @After fun clean(): Unit = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(UserManager::class.java))
            .setUserUnlocked(true)
        store.clear()
        store.closeForTests()
        val preferenceListener = AppPrefs::class.java.getDeclaredField(
            "onSharedPreferenceChangeListener").apply { isAccessible = true }
            .get(AppPrefs.getInstance()) as SharedPreferences.OnSharedPreferenceChangeListener
        stored.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        AppPrefs::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousPrefs)
        }
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
        Unit
    }

    private fun ordinary() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
    private fun enable() {
        AppPrefs.getInstance().keyboard.touchDiagnosticLogging.setValue(true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun defaultOffAndUnknownEditorDoNotEvenExecuteRecordPayload() = runBlocking {
        // Recreate after setup cleanup to prove ordinary input cannot start a writer while off.
        store.closeForTests()
        store = TouchDiagnosticStore(RuntimeEnvironment.getApplication())
        assertFalse(store.writerStartedForTests)
        assertFalse(AppPrefs.getInstance().keyboard.touchBoundarySettling.getValue())
        assertFalse(AppPrefs.getInstance().keyboard.touchDiagnosticLogging.getValue())
        var called = false
        store.updateEditor(ordinary())
        store.record("trace") { called = true }
        assertFalse(called)
        assertFalse(store.writerStartedForTests)
        enable()
        store.updateEditor(null)
        store.record("trace") { called = true }
        assertFalse(called)
        assertFalse(store.writerStartedForTests)
        assertEquals(0, store.snapshot().bytes)
    }

    @Test fun editorChangeAndClearRotateTokenAndPrivateEditorRejectsPayload() = runBlocking {
        enable()
        store.updateEditor(ordinary())
        val firstToken = store.recordingToken
        assertTrue(store.isRecording)
        store.updateEditor(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        assertFalse(store.isRecording)
        assertNotEquals(firstToken, store.recordingToken)
        var called = false
        store.record("trace") { called = true }
        assertFalse(called)
        store.updateEditor(ordinary())
        val beforeClear = store.recordingToken
        store.clear()
        assertNotEquals(beforeClear, store.recordingToken)
        assertEquals(0, store.snapshot().bytes)
    }

    @Test fun persistedEnvelopeHasRelativeTimeAndFrozenTraceModeWithoutEditorIdentity() = runBlocking {
        enable()
        store.updateEditor(ordinary().apply {
            packageName = "private.package"
            fieldId = 999
            hintText = "secret hint"
        })
        AppPrefs.getInstance().keyboard.touchBoundarySettling.setValue(false)
        store.record("trace", android.os.SystemClock.uptimeMillis()) {
            put("trace", JSONObject().put("boundary_settling", true).put("id", "gesture"))
        }
        val data = store.snapshot().content.toString(Charsets.UTF_8)
        val envelope = JSONObject(data.trim())
        assertEquals(1, envelope.getInt("schema"))
        assertEquals("on", envelope.getString("boundary_mode"))
        assertTrue(envelope.getString("session").isNotEmpty())
        assertTrue(envelope.getLong("t") >= 0)
        assertFalse(data.contains("private.package"))
        assertFalse(data.contains("secret hint"))
        assertFalse(data.contains("fieldId"))
    }

    @Test fun disablingLoggingImmediatelyRevokesRecordingAndResetsSession() = runBlocking {
        enable()
        store.updateEditor(ordinary())
        val token = store.recordingToken
        AppPrefs.getInstance().keyboard.touchDiagnosticLogging.setValue(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.isRecording)
        assertNotEquals(token, store.recordingToken)
        var called = false
        store.record("trace") { called = true }
        assertFalse(called)
    }

    @Test fun oversizedTraceIsDroppedAndCounted() = runBlocking {
        enable()
        store.updateEditor(ordinary())
        store.record("trace") { put("too_large", "x".repeat(100_000)) }
        val snapshot = store.snapshot()
        assertEquals(0, snapshot.bytes)
        assertEquals(1L, snapshot.droppedRecords)
    }

    @Test fun backgroundDisableRevokesBeforeMainThreadPreferenceCallback() = runBlocking {
        enable()
        store.updateEditor(ordinary())
        assertTrue(store.isRecording)
        val disable = Thread {
            AppPrefs.getInstance().keyboard.touchDiagnosticLogging.setValue(false)
        }.apply { start() }
        disable.join(2_000)
        assertFalse("Preference write must finish", disable.isAlive)
        // Intentionally do not drain Main: the preference callback may still be pending.
        assertFalse(store.isRecording)
        var called = false
        store.record("trace") { called = true }
        assertFalse(called)
        assertEquals(0, store.snapshot().bytes)
    }

    @Test fun lockedDeviceFailsClosedAndOnlyUnlockAllowsCredentialProtectedRecords() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val credential = application
        val deviceProtected = application.createDeviceProtectedStorageContext()
        val directory = File(credential.noBackupFilesDir, "touch-diagnostics")
        store.closeForTests()
        directory.deleteRecursively()
        val manager = application.getSystemService(UserManager::class.java)
        shadowOf(manager).setUserUnlocked(false)
        store = TouchDiagnosticStore(deviceProtected)
        enable()
        store.updateEditor(ordinary())
        assertFalse(store.isRecording)
        var called = false
        store.record("trace") { called = true }
        assertFalse(called)
        assertFalse(store.writerStartedForTests)
        assertFalse(directory.exists())
        assertEquals(0, store.status.value.bytes)
        assertEquals(0, store.snapshot().bytes)
        store.clear()
        assertFalse(store.writerStartedForTests)
        assertFalse(directory.exists())

        shadowOf(manager).setUserUnlocked(true)
        assertTrue(store.isRecording)
        store.record("trace") { put("trace", JSONObject().put("id", "after-unlock")) }
        assertEquals(1, store.snapshot().recordCount)
        assertTrue(directory.isDirectory)
        assertFalse(File(deviceProtected.noBackupFilesDir, "touch-diagnostics").exists())
    }
}
