/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.diagnostics

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Local, opt-in first-batch diagnosis. It does not change touch routing or query the engine. */
class TouchDiagnosticStore internal constructor(context: Context) {
    companion object {
        @Volatile private var instance: TouchDiagnosticStore? = null
        fun get(context: Context): TouchDiagnosticStore = instance ?: synchronized(this) {
            instance ?: TouchDiagnosticStore(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = AppPrefs.getInstance().keyboard
    // Diagnostics belong in credential-protected storage even when preferences use direct boot.
    // The manifest keeps the application context credential-protected; direct-boot preferences
    // use a separate device-protected wrapper. No hidden Android context APIs are required.
    private val credentialContext = context.applicationContext ?: context
    // Context.noBackupFilesDir may create its folder in the caller's thread. Compose the same
    // private path without touching the filesystem; only the bounded writer may create it.
    private val directory = File(credentialContext.dataDir, "no_backup/touch-diagnostics")
    private val writerDelegate = lazy {
        TouchDiagnosticWriter(TouchDiagnosticFiles(directory))
    }
    private val writer by writerDelegate
    private val emptyStatus = MutableStateFlow(TouchDiagnosticStatus())
    @Volatile private var unlocked = false

    private fun storageAvailable(): Boolean {
        if (credentialContext.isDeviceProtectedStorage) return false
        // Before the first unlock, fail closed. Once unlocked, credential storage stays available
        // across ordinary screen locks; AXiang restarts when leaving its direct-boot mode.
        if (unlocked) return true
        if (credentialContext.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return false
        unlocked = true
        return true
    }

    val status: StateFlow<TouchDiagnosticStatus>
        get() = if (storageAvailable()) writer.status else emptyStatus
    private data class Session(val id: String, val started: Long, val wallTime: Long)
    @Volatile private var session = newSession()
    @Volatile private var enabled = prefs.touchDiagnosticLogging.getValue()
    @Volatile private var allowedEditor = false
    private val token = AtomicLong()

    private fun newSession() = Session(UUID.randomUUID().toString(),
        SystemClock.uptimeMillis(), System.currentTimeMillis())

    private val loggingListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        enabled = value
        rotateSession()
    }

    init { prefs.touchDiagnosticLogging.registerOnChangeListener(loggingListener) }

    // SharedPreferences reads are in-memory. The final check revokes a background disable
    // before Android delivers its preference-change callback on the main thread.
    val isRecording: Boolean get() = enabled && allowedEditor &&
        prefs.touchDiagnosticLogging.getValue() && storageAvailable()
    /** Capture at trace start and reject completion if it changed, even when recording resumed. */
    val recordingToken: Long get() = token.get()

    private fun rotateSession() {
        token.incrementAndGet()
        session = newSession()
    }

    fun updateEditor(info: EditorInfo?) {
        // Revoke immediately; never include app package, field IDs, surrounding text or hints.
        allowedEditor = false
        rotateSession()
        allowedEditor = TouchDiagnosticPolicy.allows(info)
    }

    fun relativeTime(eventTime: Long): Long = (eventTime - session.started).coerceAtLeast(0)

    fun record(
        kind: String,
        eventTime: Long = SystemClock.uptimeMillis(),
        fields: JSONObject.() -> Unit = {}
    ) {
        if (!isRecording) return
        val version = token.get()
        val currentSession = session
        // Diagnostics must never throw into input dispatch, nor perform storage IO here.
        val encoded = runCatching {
            JSONObject().apply {
                fields()
                put("schema", 1)
                put("session", currentSession.id)
                put("session_started_at", currentSession.wallTime)
                put("t", (eventTime - currentSession.started).coerceAtLeast(0))
                put("kind", kind)
                val boundaryEnabled = if (kind == "trace") {
                    optJSONObject("trace")?.let { trace ->
                        if (trace.has("boundary_settling")) trace.optBoolean("boundary_settling")
                        else null
                    } ?: prefs.touchBoundarySettling.getValue()
                } else prefs.touchBoundarySettling.getValue()
                put("boundary_mode", if (boundaryEnabled) "on" else "off")
            }.toString().plus("\n").toByteArray(Charsets.UTF_8)
        }.getOrNull() ?: return
        if (encoded.size > TouchDiagnosticFiles.MAXIMUM_RECORD_BYTES) {
            writer.rejectedRecord()
            return
        }
        writer.append(encoded) { isRecording && token.get() == version }
    }

    suspend fun snapshot(): TouchDiagnosticSnapshot = if (storageAvailable()) writer.snapshot()
        else TouchDiagnosticSnapshot(0, byteArrayOf(), 0, 0, null, null, 0)
    suspend fun clear() {
        rotateSession()
        if (storageAvailable()) writer.clear()
    }
    /** Ordered export of the exact preview, rejected if Clear invalidated it. */
    suspend fun export(snapshot: TouchDiagnosticSnapshot, output: OutputStream) {
        check(storageAvailable()) { "Diagnostics unavailable before device unlock" }
        writer.export(snapshot, output)
    }

    internal val writerStartedForTests: Boolean get() = writerDelegate.isInitialized()

    internal suspend fun closeForTests() {
        prefs.touchDiagnosticLogging.unregisterOnChangeListener(loggingListener)
        if (writerDelegate.isInitialized()) writer.close()
    }
}
