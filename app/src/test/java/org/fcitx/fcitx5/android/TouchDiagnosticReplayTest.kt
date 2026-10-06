/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewConfiguration
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt

/** Canonical replay: feeds captured MotionEvents to the actual production TextKeyboard.
 * No reimplementation of hit testing or TapRetargetGuard lives in the Python CLI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchDiagnosticReplayTest {
    private var previousApplication: Any? = null

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        field.set(null, app)
        AppPrefs.init(context.getSharedPreferences("touch-diagnostic-replay", Context.MODE_PRIVATE))
    }

    @After fun restore() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, previousApplication)
        }
    }

    private class Unsupported(message: String) : IllegalArgumentException(message)
    private data class Finger(val id: Int, val x: Float, val y: Float)

    private class Harness(val layout: JSONObject? = null, boundary: Boolean = true) : AutoCloseable {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val width = layout?.getInt("width") ?: (360 * density).roundToInt()
        val height = layout?.getInt("height") ?: (260 * density).roundToInt()
        val keyboard: TextKeyboard
        val actions = mutableListOf<JSONObject>()
        val captured = mutableListOf<JSONObject>()
        var epoch = 0L
        private var downTime = 0L

        init {
            val p = ThemeManager.prefs
            val k = AppPrefs.getInstance().keyboard
            val settings = layout?.getJSONObject("settings") ?: JSONObject()
            restoreRecordedTouchSlop(settings)
            setting(p.pressEffect, false)
            setting(p.idleBreathing, false)
            setting(p.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            setting(p.portraitNumberRow, settings.optBoolean("portrait_number_row", false))
            setting(p.keyWidthOverrides, settings.optString("key_width_overrides", ""))
            setting(k.popupOnKeyPress, settings.optBoolean("popup_on_key_press", false))
            setting(k.expandKeypressArea, settings.optBoolean("expand_keypress_area", true))
            setting(k.longPressDelay, settings.optInt("long_press_delay", 300))
            setting(k.keepLettersUppercase, settings.optBoolean("keep_letters_uppercase", true))
            setting(k.showLangSwitchKey, settings.optBoolean("show_lang_switch_key", true))
            setting(k.spaceSwipeMoveCursor, settings.optBoolean("space_swipe_move_cursor", true))
            settings.optString("space_long_press_behavior", "").takeIf { it.isNotEmpty() }?.let { name ->
                setting(k.spaceKeyLongPressBehavior, k.spaceKeyLongPressBehavior.getValue().javaClass.enumConstants.first { it.name == name })
            }
            settings.optString("swipe_symbol_direction", "").takeIf { it.isNotEmpty() }?.let { name ->
                setting(k.swipeSymbolDirection, k.swipeSymbolDirection.getValue().javaClass.enumConstants.first { it.name == name })
            }
            setting(AppPrefs.getInstance().advanced.vivoKeypressWorkaround,
                settings.optBoolean("vivo_keypress_workaround", false))
            setting(k.touchBoundarySettling, boundary)
            // Replay touch routing independently of optional language correction.
            setting(k.pinyinTouchCorrection, false)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(FrameLayout(activity).apply {
                addView(keyboard, FrameLayout.LayoutParams(this@Harness.width, this@Harness.height))
            })
            controller.visible()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, width, height)
            keyboard.keyActionListener = KeyActionListener { action, source ->
                actions += JSONObject().put("t", SystemClock.uptimeMillis() - epoch)
                    .put("type", action.javaClass.simpleName).put("source", source.name).also {
                        when (action) {
                            is KeyAction.FcitxKeyAction -> it.put("act", action.act)
                            is KeyAction.SymAction -> it.put("sym", action.sym.sym)
                            is KeyAction.CommitAction -> it.put("text", action.text)
                            else -> Unit
                        }
                    }
            }
            keyboard.popupActionListener = PopupActionListener { }
            keyboard.touchDiagnosticObserver = { captured += JSONObject(it.toString()) }
            if (layout != null) try {
                verifyLayout(layout)
            } catch (error: Exception) {
                close()
                throw error
            }
        }

        private fun <T : Any> setting(pref: ManagedPreference<T>, value: T) {
            val existed = pref.sharedPreferences.contains(pref.key)
            val old = pref.getValue()
            restore += { if (existed) pref.setValue(old) else pref.sharedPreferences.edit().remove(pref.key).commit(); Unit }
            pref.setValue(value)
        }

        private fun restoreRecordedTouchSlop(settings: JSONObject) {
            if (!settings.has("touch_slop_px")) return
            val recorded = settings.getDouble("touch_slop_px")
            if (!recorded.isFinite() || recorded <= 0 || recorded > Int.MAX_VALUE || recorded != recorded.toInt().toDouble())
                throw Unsupported("Recorded touch slop must be a finite positive integer")
            // Robolectric 4.17 uses a fixed 16dp slop, independent of device resources.
            // Restore its cached configuration before production keyboard/child views
            // read it in their constructors; keep the exact verification below.
            val configuration = ViewConfiguration.get(activity)
            val shadow = Shadow.extract<ShadowViewConfiguration>(configuration)
            val original = configuration.scaledTouchSlop
            restore += { ReflectionHelpers.setField(shadow, "touchSlop", original) }
            ReflectionHelpers.setField(shadow, "touchSlop", recorded.toInt())
        }

        private fun keys(view: View = keyboard): List<KeyView> = when (view) {
            is KeyView -> if (view.visibility == View.VISIBLE) listOf(view) else emptyList()
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }
        private fun label(key: KeyView) = (key.def as? KeyDef.Appearance.Text)?.displayText ?: key.def.javaClass.simpleName
        fun bounds(label: String): Rect {
            var child: View = keys().first { label(it) == label }
            val bounds = Rect(0, 0, child.width, child.height)
            while (child !== keyboard) { bounds.offset(child.left, child.top); child = child.parent as View }
            return bounds
        }
        fun center(label: String, id: Int = 0) = bounds(label).let { Finger(id, it.exactCenterX(), it.exactCenterY()) }

        private fun verifyLayout(recorded: JSONObject) {
            if (recorded.optString("name", "Text") != "Text") throw Unsupported("Only Text keyboard replay is supported")
            if (abs(recorded.getDouble("density") - density) > .001) throw Unsupported("Density reconstruction differs")
            val settings = recorded.getJSONObject("settings")
            if (settings.has("touch_slop_px") && abs(settings.getDouble("touch_slop_px") - ViewConfiguration.get(activity).scaledTouchSlop) > .001)
                throw Unsupported("Device touch slop differs; refusing different gesture thresholds")
            if (!settings.optBoolean("slide_selection_enabled", true) || !settings.optBoolean("guard_tap_retargeting", true))
                throw Unsupported("Recorded routing profile differs from TextKeyboard")
            val recordedKeys = recorded.getJSONArray("keys")
            val actual = keys()
            if (recordedKeys.length() != actual.size) throw Unsupported("Visible key count differs")
            for (index in actual.indices) {
                val expected = recordedKeys.getJSONObject(index)
                val key = actual[index]
                var child: View = key
                val rect = Rect(0, 0, child.width, child.height)
                while (child !== keyboard) { rect.offset(child.left, child.top); child = child.parent as View }
                val coordinates = expected.getJSONArray("rect")
                if (expected.getString("label") != label(key) ||
                    listOf(rect.left, rect.top, rect.right, rect.bottom).indices.any { coordinates.getInt(it) != listOf(rect.left, rect.top, rect.right, rect.bottom)[it] })
                    throw Unsupported("Key $index label/geometry differs; refusing approximate replay")
            }
            val state = recorded.optJSONObject("state")
            if (state != null) {
                // Restore the recorded transform, rather than silently replaying caps/Chinese keys in default mode.
                val capsName = state.optString("caps_state", "None")
                ReflectionHelpers.setField(keyboard, "capsState", TextKeyboard.CapsState.valueOf(capsName))
                ReflectionHelpers.setField(keyboard, "englishMode", state.optBoolean("english_mode", false))
                ReflectionHelpers.setField(keyboard, "chineseMode", state.optBoolean("chinese_mode", false))
                val mapping = state.optJSONObject("punctuation_mapping") ?: JSONObject()
                ReflectionHelpers.setField(keyboard, "punctuationMapping", mapping.keys().asSequence().associateWith { mapping.getString(it) })
            }
        }

        private fun coords(pointer: JSONObject) = MotionEvent.PointerCoords().apply {
            x = pointer.getDouble("x").toFloat(); y = pointer.getDouble("y").toFloat()
            pressure = pointer.optDouble("pressure", 1.0).toFloat()
            size = pointer.optDouble("size", 1.0).toFloat()
            touchMajor = pointer.optDouble("touch_major", 0.0).toFloat()
            touchMinor = pointer.optDouble("touch_minor", 0.0).toFloat()
        }
        private fun coordinates(pointers: JSONArray) = Array(pointers.length()) { coords(pointers.getJSONObject(it)) }

        fun dispatch(record: JSONObject) {
            val relative = record.getLong("t")
            val action = record.getInt("action")
            val actionIndex = record.optInt("action_index", 0)
            val pointers = record.getJSONArray("pointers")
            require(pointers.length() in 1..32 && actionIndex in 0 until pointers.length())
            if (action == MotionEvent.ACTION_DOWN) downTime = epoch + record.optLong("down_time", relative)
            val properties = Array(pointers.length()) { index -> MotionEvent.PointerProperties().apply {
                id = pointers.getJSONObject(index).getInt("id")
                toolType = pointers.getJSONObject(index).optInt("tool_type", MotionEvent.TOOL_TYPE_FINGER)
            } }
            val encodedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val history = record.optJSONArray("history") ?: JSONArray()
            if (history.length() > 0 && action != MotionEvent.ACTION_MOVE) throw Unsupported("History on non-MOVE event")
            var lastTime = -1L
            for (i in 0 until history.length()) {
                val sample = history.getJSONObject(i)
                val time = sample.getLong("t")
                if (time <= lastTime || time > relative) throw Unsupported("Non-monotonic historical samples")
                lastTime = time
                val samples = sample.getJSONArray("pointers")
                if (samples.length() != pointers.length() || (0 until samples.length()).any {
                    samples.getJSONObject(it).getInt("id") != pointers.getJSONObject(it).getInt("id") })
                    throw Unsupported("Historical pointer identities differ")
            }
            val first = if (history.length() == 0) record else history.getJSONObject(0)
            val event = MotionEvent.obtain(downTime, epoch + first.getLong("t"), encodedAction, pointers.length(),
                properties, coordinates(first.getJSONArray("pointers")), record.optInt("meta_state", 0),
                record.optInt("button_state", 0), record.optDouble("x_precision", 1.0).toFloat(),
                record.optDouble("y_precision", 1.0).toFloat(), record.optInt("device_id", 0),
                record.optInt("edge_flags", 0), record.optInt("source", InputDevice.SOURCE_TOUCHSCREEN), record.optInt("flags", 0))
            try {
                if (history.length() > 0) {
                    for (i in 1 until history.length()) event.addBatch(epoch + history.getJSONObject(i).getLong("t"),
                        coordinates(history.getJSONObject(i).getJSONArray("pointers")), 0)
                    event.addBatch(epoch + relative, coordinates(pointers), 0)
                }
                val now = SystemClock.uptimeMillis()
                require(epoch + relative >= now) { "Trace time moved backwards" }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(epoch + relative - now))
                keyboard.dispatchTouchEvent(event)
                if (keyboard.width != width || keyboard.height != height) throw Unsupported("Viewport relayout differs")
            } finally { event.recycle() }
        }

        fun start() { epoch = SystemClock.uptimeMillis() }
        fun event(t: Long, action: Int, vararg fingers: Finger, actionIndex: Int = 0) = JSONObject()
            .put("t", t).put("action", action).put("action_index", actionIndex)
            .put("pointers", JSONArray(fingers.map { JSONObject().put("id", it.id).put("x", it.x).put("y", it.y)
                .put("pressure", .7).put("size", .2).put("touch_major", 12).put("touch_minor", 8) }))
            .put("history", JSONArray())
        override fun close() {
            keyboard.touchDiagnosticObserver = null
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    private fun replay(trace: JSONObject, boundary: Boolean): JSONObject {
        val result = JSONObject().put("schema", 1).put("kind", "replay").put("trace_id", trace.getString("id"))
            .put("boundary_settling", boundary).put("submission_policy", "current_up").put("typed", "")
        return try {
            val layout = trace.getJSONObject("layout")
            val densityDpi = (layout.getDouble("density") * 160).roundToInt()
            val orientation = if (layout.getInt("orientation") == 2) "land" else "port"
            RuntimeEnvironment.setQualifiers("zh-rCN-w600dp-h900dp-$orientation-${densityDpi}dpi")
            Harness(layout, boundary).use { h ->
                h.start()
                val events = trace.getJSONArray("events")
                if (events.length() == 0 || events.getJSONObject(0).getInt("action") != MotionEvent.ACTION_DOWN)
                    throw Unsupported("Trace has no initial DOWN")
                for (i in 0 until events.length()) h.dispatch(events.getJSONObject(i))
                val captured = h.captured.lastOrNull()
                val replayActions = captured?.optJSONArray("actions") ?: JSONArray(h.actions)
                fun typed(actions: JSONArray) = (0 until actions.length()).mapNotNull { index ->
                    actions.getJSONObject(index).optString("act", "").takeIf { act -> act.length == 1 && act[0] in 'a'..'z' }
                }.joinToString("")
                fun signatures(actions: JSONArray) = (0 until actions.length()).map { index ->
                    val action = actions.getJSONObject(index)
                    listOf("type", "act", "text", "sym", "states", "code", "source").map { key -> action.opt(key)?.toString() }
                }
                result.put("status", "ok").put("typed", typed(replayActions)).put("actions", replayActions)
                    .put("decisions", captured?.optJSONArray("decisions") ?: JSONArray())
                    .put("contacts", captured?.optJSONArray("contacts") ?: JSONArray())
                    .put("geometry_verified", true).put("synthetic", trace.optBoolean("synthetic", false))
                    .put("recorded_typed", typed(trace.getJSONArray("actions")))
                    .put("recorded_mode", trace.getBoolean("boundary_settling"))
                    .put("matches_recorded_actions", signatures(replayActions) == signatures(trace.getJSONArray("actions")))
                if (boundary == trace.getBoolean("boundary_settling") && !result.getBoolean("matches_recorded_actions"))
                    result.put("status", "baseline_fidelity_mismatch").put("reason", "Recorded mode did not reproduce original actions")
                result
            }
        } catch (error: Exception) {
            result.put("status", "unsupported").put("reason", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun synthetic(name: String, build: (Harness) -> List<JSONObject>): JSONObject = Harness().use { h ->
        h.start()
        build(h).forEach(h::dispatch)
        assertEquals("Fixture must emit exactly one completed real diagnostic trace", 1, h.captured.size)
        JSONObject(h.captured.single().toString()).put("id", name).put("synthetic", true)
    }

    private fun fixtures(): List<JSONObject> = listOf(
        synthetic("synthetic-fast-boundary-drift") { h ->
            val from = h.bounds("F"); val to = h.bounds("G")
            val down = Finger(0, from.right - h.density, from.exactCenterY())
            val drift = Finger(0, to.left + 8 * h.density, to.exactCenterY())
            listOf(h.event(0, MotionEvent.ACTION_DOWN, down), h.event(8, MotionEvent.ACTION_MOVE, drift),
                h.event(56, MotionEvent.ACTION_MOVE, drift), h.event(64, MotionEvent.ACTION_UP, drift))
        },
        synthetic("synthetic-fast-historical-drift") { h ->
            val from = h.bounds("F"); val to = h.bounds("G")
            val down = Finger(0, from.right - h.density, from.exactCenterY())
            val drift = Finger(0, to.left + 8 * h.density, to.exactCenterY())
            val move = h.event(160, MotionEvent.ACTION_MOVE, drift).put("history", JSONArray(listOf(
                h.event(8, MotionEvent.ACTION_MOVE, drift), h.event(56, MotionEvent.ACTION_MOVE, drift))))
            listOf(h.event(0, MotionEvent.ACTION_DOWN, down), move, h.event(168, MotionEvent.ACTION_UP, drift))
        },
        synthetic("synthetic-slow-boundary-settle") { h ->
            val from = h.bounds("F"); val to = h.bounds("G")
            val down = Finger(0, from.right - h.density, from.exactCenterY())
            val drift = Finger(0, to.left + 8 * h.density, to.exactCenterY())
            listOf(h.event(0, MotionEvent.ACTION_DOWN, down), h.event(200, MotionEvent.ACTION_MOVE, drift),
                h.event(248, MotionEvent.ACTION_MOVE, drift), h.event(256, MotionEvent.ACTION_UP, drift))
        },
        synthetic("synthetic-slow-historical-settle") { h ->
            val from = h.bounds("F"); val to = h.bounds("G")
            val down = Finger(0, from.right - h.density, from.exactCenterY())
            val drift = Finger(0, to.left + 8 * h.density, to.exactCenterY())
            val move = h.event(288, MotionEvent.ACTION_MOVE, drift).put("history", JSONArray(listOf(
                h.event(200, MotionEvent.ACTION_MOVE, drift), h.event(248, MotionEvent.ACTION_MOVE, drift))))
            listOf(h.event(0, MotionEvent.ACTION_DOWN, down), move, h.event(296, MotionEvent.ACTION_UP, drift))
        },
        synthetic("synthetic-deliberate-slide") { h ->
            listOf(h.event(0, MotionEvent.ACTION_DOWN, h.center("G")), h.event(8, MotionEvent.ACTION_MOVE, h.center("H")),
                h.event(72, MotionEvent.ACTION_MOVE, h.center("H")), h.event(80, MotionEvent.ACTION_UP, h.center("H")))
        },
        synthetic("synthetic-up-drift") { h ->
            listOf(h.event(0, MotionEvent.ACTION_DOWN, h.center("F")), h.event(16, MotionEvent.ACTION_UP, h.center("G")))
        },
        synthetic("synthetic-reverse-up-order") { h ->
            listOf(h.event(0, MotionEvent.ACTION_DOWN, h.center("T", 3)),
                h.event(8, MotionEvent.ACTION_POINTER_DOWN, h.center("T", 3), h.center("L", 9), actionIndex = 1),
                h.event(16, MotionEvent.ACTION_POINTER_UP, h.center("T", 3), h.center("L", 9), actionIndex = 1),
                h.event(24, MotionEvent.ACTION_UP, h.center("T", 3)))
        }
    )

    @Test fun realKeyboardReplayChangesOnlyBoundarySettlingAndPreservesUpOrder() {
        val expected = mapOf("synthetic-fast-boundary-drift" to Pair("f", "f"), "synthetic-fast-historical-drift" to Pair("f", "f"),
            "synthetic-slow-boundary-settle" to Pair("g", "f"), "synthetic-slow-historical-settle" to Pair("g", "f"),
            "synthetic-deliberate-slide" to Pair("h", "h"), "synthetic-up-drift" to Pair("f", "f"),
            "synthetic-reverse-up-order" to Pair("lt", "lt"))
        fixtures().forEach { trace ->
            val (on, off) = expected.getValue(trace.getString("id"))
            for ((mode, value) in listOf(true to on, false to off)) {
                val result = replay(trace, mode)
                assertEquals(result.toString(), "ok", result.getString("status"))
                assertEquals(trace.getString("id"), value, result.getString("typed"))
            }
        }
    }

    @Test fun mismatchedGeometryIsExplicitlyRejected() {
        val trace = fixtures().first()
        val first = trace.getJSONObject("layout").getJSONArray("keys").getJSONObject(0).getJSONArray("rect")
        first.put(0, first.getInt(0) + 1)
        val result = replay(trace, false)
        assertEquals("unsupported", result.getString("status"))
        assertTrue(result.getString("reason").contains("geometry"))
    }

    @Test fun recordedTouchSlopIsConsumedBeforeGestureConstructionAndRestored() {
        val trace = fixtures().first()
        val layout = trace.getJSONObject("layout")
        val configuration = ViewConfiguration.get(RuntimeEnvironment.getApplication())
        val original = configuration.scaledTouchSlop
        val recorded = if (original == 8) 9 else 8
        layout.getJSONObject("settings").put("touch_slop_px", recorded)
        Harness(layout).use { h ->
            assertEquals(recorded, ViewConfiguration.get(h.keyboard.context).scaledTouchSlop)
            assertEquals(recorded.toFloat(), ReflectionHelpers.getField<Float>(h.keyboard, "slideTouchSlop"), 0f)
            fun verifyChildren(view: View) {
                if (view is KeyView)
                    assertEquals(recorded.toFloat(), ReflectionHelpers.getField<Float>(view, "touchSlop"), 0f)
                if (view is ViewGroup)
                    for (index in 0 until view.childCount) verifyChildren(view.getChildAt(index))
            }
            verifyChildren(h.keyboard)
        }
        assertEquals(original, configuration.scaledTouchSlop)
    }

    /** CLI entry: env paths are forwarded by scripts/touch-diagnostics/jvm-tests.init.gradle. */
    @Test fun exportedTraces() {
        val input = System.getenv("AXIANG_TOUCH_REPLAY_INPUT")
        val syntheticPath = System.getenv("AXIANG_TOUCH_REPLAY_SYNTHETIC")
        if (input == null && syntheticPath == null) return
        val traces = if (input != null) File(input).readLines().filter { it.isNotBlank() }.map { line ->
            val record = JSONObject(line)
            require(record.getInt("schema") == 1 && record.getString("kind") == "trace")
            record.getJSONObject("trace")
        } else fixtures().also { traces ->
            File(syntheticPath!!).writeText(traces.joinToString("\n", postfix = "\n") { trace ->
                JSONObject().put("schema", 1).put("kind", "trace").put("session", "synthetic")
                    .put("trace", trace).toString()
            })
        }
        val output = System.getenv("AXIANG_TOUCH_REPLAY_OUTPUT") ?: error("AXIANG_TOUCH_REPLAY_OUTPUT is required")
        File(output).writeText(traces.flatMap { trace -> listOf(replay(trace, true), replay(trace, false)) }
            .joinToString("\n", postfix = "\n") { it.toString() })
    }
}
