/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Observes original MotionEvents; never supplies events or decisions to the keyboard. */
internal class TouchTraceRecorder(context: Context) {
    private val store by lazy { TouchDiagnosticStore.get(context) }

    /** Internal memory-only test sink. Production has no observer and obeys the store consent gate. */
    var observer: ((JSONObject) -> Unit)? = null

    private class Trace(
        val downAt: Long,
        val token: Long?,
        val body: JSONObject,
        val events: JSONArray = JSONArray(),
        val decisions: JSONArray = JSONArray(),
        val contacts: JSONArray = JSONArray(),
        val actions: JSONArray = JSONArray(),
        val activeContacts: MutableMap<Int, JSONObject> = mutableMapOf(),
        var sampleCount: Int = 0
    )

    private var trace: Trace? = null
    private var dispatchPointer: Int? = null
    private var dispatchKey: Int? = null

    internal val currentTraceId: String?
        get() = trace?.takeIf(::valid)?.body?.optString("id")
    internal val currentRecordingToken: Long?
        get() = trace?.takeIf(::valid)?.token

    private fun valid(current: Trace): Boolean = observer != null ||
        (store.isRecording && current.token == store.recordingToken)

    fun discard() {
        trace = null
        dispatchPointer = null
        dispatchKey = null
    }

    fun beforeEvent(event: MotionEvent, boundarySettling: Boolean, layout: () -> JSONObject,
                    hitKey: (Float, Float) -> Int?) {
        runCatching { observeEvent(event, boundarySettling, layout, hitKey) }.onFailure { discard() }
    }

    private fun observeEvent(event: MotionEvent, boundarySettling: Boolean, layout: () -> JSONObject,
                             hitKey: (Float, Float) -> Int?) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            discard()
            if (observer == null && !store.isRecording) return
            runCatching {
                trace = Trace(event.eventTime, if (observer == null) store.recordingToken else null,
                    JSONObject().put("id", UUID.randomUUID().toString())
                        .put("boundary_settling", boundarySettling).put("layout", layout()))
            }.onFailure { discard() }
        }
        val current = trace ?: return
        if (!valid(current) || event.eventTime - current.downAt !in 0L..MAX_DURATION_MS ||
            current.events.length() >= MAX_EVENTS || event.pointerCount > MAX_POINTERS ||
            current.sampleCount + event.pointerCount * (event.historySize + 1) > MAX_SAMPLES) {
            discard()
            return
        }
        runCatching {
            current.sampleCount += event.pointerCount * (event.historySize + 1)
            val history = JSONArray()
            for (h in 0 until event.historySize) history.put(JSONObject()
                .put("t", event.getHistoricalEventTime(h) - current.downAt)
                .put("pointers", pointers(event, h)))
            current.events.put(JSONObject().put("t", event.eventTime - current.downAt)
                .put("action", event.actionMasked).put("action_index", event.actionIndex)
                .put("down_time", event.downTime - current.downAt)
                .put("meta_state", event.metaState).put("button_state", event.buttonState)
                .put("source", event.source).put("device_id", event.deviceId)
                .put("edge_flags", event.edgeFlags).put("flags", event.flags)
                .put("x_precision", event.xPrecision).put("y_precision", event.yPrecision)
                .put("pointers", pointers(event)).put("history", history))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val contact = JSONObject().put("pointer_id", event.getPointerId(i))
                        .put("down_t", event.eventTime - current.downAt)
                        .put("down_key", hitKey(event.getX(i), event.getY(i)) ?: JSONObject.NULL)
                        .put("down", JSONArray().put(event.getX(i)).put(event.getY(i)))
                    current.contacts.put(contact)
                    current.activeContacts[event.getPointerId(i)] = contact
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    current.activeContacts[event.getPointerId(i)]?.apply {
                        put("up_t", event.eventTime - current.downAt)
                        put("up", JSONArray().put(event.getX(i)).put(event.getY(i)))
                        put("up_hit_key", hitKey(event.getX(i), event.getY(i)) ?: JSONObject.NULL)
                        if (!has("release_reason")) put("up_key", JSONObject.NULL)
                    }
                }
                MotionEvent.ACTION_CANCEL -> current.activeContacts.values.forEach {
                    if (!it.has("release_reason"))
                        it.put("cancelled", true).put("cancel_t", event.eventTime - current.downAt)
                }
            }
        }.onFailure { discard() }
    }

    private fun pointers(event: MotionEvent, historicalIndex: Int? = null): JSONArray = JSONArray().apply {
        for (i in 0 until event.pointerCount) put(JSONObject()
            .put("id", event.getPointerId(i))
            .put("tool_type", event.getToolType(i))
            .put("x", historicalIndex?.let { event.getHistoricalX(i, it) } ?: event.getX(i))
            .put("y", historicalIndex?.let { event.getHistoricalY(i, it) } ?: event.getY(i))
            .put("pressure", historicalIndex?.let { event.getHistoricalPressure(i, it) } ?: event.getPressure(i))
            .put("size", historicalIndex?.let { event.getHistoricalSize(i, it) } ?: event.getSize(i))
            .put("touch_major", historicalIndex?.let { event.getHistoricalTouchMajor(i, it) } ?: event.getTouchMajor(i))
            .put("touch_minor", historicalIndex?.let { event.getHistoricalTouchMinor(i, it) } ?: event.getTouchMinor(i)))
    }

    fun released(pointerId: Int, keyId: Int, cancelled: Boolean = false,
                 reason: String? = null, time: Long? = null) {
        runCatching {
            val current = trace?.takeIf(::valid) ?: return
            current.activeContacts[pointerId]?.apply {
                put("up_key", keyId)
                if (cancelled) put("cancelled", true)
                reason?.let { put("release_reason", it) }
                time?.let { put("release_t", it - current.downAt) }
            }
        }.onFailure { discard() }
    }

    fun decision(time: Long, pointerId: Int, from: Int, to: Int, slide: Boolean) {
        runCatching {
            val current = trace ?: return
            if (!valid(current) || current.decisions.length() >= MAX_DECISIONS) {
                discard()
                return
            }
            current.decisions.put(JSONObject().put("t", time - current.downAt)
                .put("pointer_id", pointerId).put("from", from).put("to", to)
                .put("mode", if (slide) "slide" else "settle"))
        }.onFailure { discard() }
    }

    fun <T> dispatch(pointerId: Int, keyId: Int, block: () -> T): T {
        val previousPointer = dispatchPointer
        val previousKey = dispatchKey
        dispatchPointer = pointerId
        dispatchKey = keyId
        try { return block() } finally {
            dispatchPointer = previousPointer
            dispatchKey = previousKey
        }
    }

    fun action(action: KeyAction, source: KeyActionListener.Source) {
        runCatching { observeAction(action, source) }.onFailure { discard() }
    }

    private fun observeAction(action: KeyAction, source: KeyActionListener.Source) {
        val current = trace ?: return
        if (!valid(current) || current.actions.length() >= MAX_ACTIONS) {
            discard()
            return
        }
        val payload = JSONObject().put("t", SystemClock.uptimeMillis() - current.downAt)
            .put("type", action.javaClass.simpleName).put("source", source.name)
        dispatchPointer?.let { payload.put("pointer_id", it) }
        dispatchKey?.let { payload.put("key_id", it) }
        when (action) {
            is KeyAction.FcitxKeyAction -> payload.put("act", action.act).put("code", action.code)
                .put("states", action.states.toInt())
            is KeyAction.SymAction -> payload.put("sym", action.sym.sym).put("states", action.states.toInt())
            is KeyAction.CommitAction -> payload.put("text", action.text)
            is KeyAction.CapsAction -> payload.put("lock", action.lock)
            is KeyAction.LayoutSwitchAction -> payload.put("act", action.act)
            is KeyAction.MoveSelectionAction -> payload.put("start", action.start).put("end", action.end)
            is KeyAction.DeleteSelectionAction -> payload.put("total_count", action.totalCnt)
            else -> {}
        }
        current.actions.put(payload)
    }

    fun afterEvent(event: MotionEvent) {
        runCatching { finishEvent(event) }.onFailure { discard() }
    }

    private fun finishEvent(event: MotionEvent) {
        val current = trace ?: return
        if (!valid(current)) { discard(); return }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP)
            current.activeContacts.remove(event.getPointerId(event.actionIndex))
        if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) return
        val completed = current.body.put("events", current.events).put("decisions", current.decisions)
            .put("contacts", current.contacts).put("actions", current.actions)
            .put("cancelled", event.actionMasked == MotionEvent.ACTION_CANCEL)
        val sink = observer
        discard()
        if (sink != null) sink(completed)
        else if (store.isRecording && current.token == store.recordingToken)
            store.record("trace", event.eventTime) { put("trace", completed) }
    }

    companion object {
        // Oversized/incomplete gestures are dropped, never partially interpreted as taps.
        private const val MAX_DURATION_MS = 30_000L
        private const val MAX_EVENTS = 512
        private const val MAX_SAMPLES = 4096
        private const val MAX_POINTERS = 16
        private const val MAX_DECISIONS = 256
        private const val MAX_ACTIONS = 256
    }
}
