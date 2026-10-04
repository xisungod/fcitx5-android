/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.*
import org.fcitx.fcitx5.android.input.bar.ui.idle.KeyboardLayoutChoice
import org.fcitx.fcitx5.android.input.bar.ui.idle.keyboardLayoutMenu
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PinyinT9KeyboardTest {
    private var oldApplication: Any? = null

    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        oldApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, app)
        AppPrefs.init(application.getSharedPreferences("pinyin-t9-keyboard-test", Context.MODE_PRIVATE))
    }

    @After fun restoreApplication() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, oldApplication)
        }
    }

    private class Harness(val width: Int = 600, val height: Int = 420, effects: Boolean = false) {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).also {
            it.get().setTheme(R.style.Theme_InputViewTheme)
        }.setup()
        private val activity = controller.get()
        val keyboard: PinyinT9Keyboard
        val actions = mutableListOf<KeyAction>()
        private var downTime = 0L

        init {
            val prefs = AppPrefs.getInstance().keyboard
            setting(prefs.longPressDelay, 300)
            setting(prefs.hapticStrength, 0)
            setting(prefs.popupOnKeyPress, true)
            setting(prefs.spaceKeyLongPressBehavior, SpaceLongPressBehavior.MoveCursor)
            setting(prefs.spaceSwipeMoveCursor, false)
            setting(AppPrefs.getInstance().advanced.disableAnimation, false)
            val theme = ThemeManager.prefs
            setting(theme.pressEffect, effects)
            setting(theme.idleBreathing, false)
            setting(theme.effectsFollowSystemAnimation, false)
            setting(theme.keyMotionEffect, if (effects) ThemePrefs.KeyMotionEffect.Press else ThemePrefs.KeyMotionEffect.Off)
            setting(theme.pressColorMode, ThemePrefs.PressColorMode.Random)
            setting(theme.pressEffectPalette, ThemePrefs.PressEffectPalette.SamsungCool)
            setting(theme.rippleShape, ThemePrefs.RippleShape.SoftMist)
            setting(theme.portraitNumberRow, true)
            setting(theme.punctuationPosition, ThemePrefs.PunctuationPosition.None)
            keyboard = PinyinT9Keyboard(activity, ThemePreset.XuancaiBlackV09)
            keyboard.keyActionListener = KeyActionListener { value, _ -> actions += value }
            activity.setContentView(keyboard)
            controller.visible()
            advance(32)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, width, height)
        }

        private fun <T : Any> setting(pref: ManagedPreference<T>, value: T) {
            val existed = pref.sharedPreferences.contains(pref.key)
            val before = pref.getValue()
            restore += {
                if (existed) pref.setValue(before) else pref.sharedPreferences.edit().remove(pref.key).commit()
                Unit
            }
            pref.setValue(value)
        }

        fun keys(view: View = keyboard): List<KeyView> = when (view) {
            is KeyView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }

        fun textKey(label: String) = keys().filterIsInstance<TextKeyView>().first {
            (it.def as KeyDef.Appearance.Text).displayText == label
        }

        fun bounds(key: View): Rect = Rect().also {
            key.getDrawingRect(it)
            keyboard.offsetDescendantRectToMyCoords(key, it)
        }

        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

        fun touch(key: View, action: Int, dx: Float = 0f, dy: Float = 0f) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val rect = bounds(key)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                rect.exactCenterX() + dx, rect.exactCenterY() + dy, 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
        }

        fun tap(key: View) {
            touch(key, MotionEvent.ACTION_DOWN)
            advance(30)
            touch(key, MotionEvent.ACTION_UP)
        }

        fun capture(name: String) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            repeat(2) { keyboard.draw(Canvas(bitmap).apply { drawColor(Color.BLACK) }) }
            val output = File("build/outputs/effect-checks/$name.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        fun finish() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    @Test fun realDigitTouchesSendAsciiT9CodesRatherThanNumpadKeysOrLiteralCommits() {
        val h = Harness()
        try {
            assertEquals("The top digit row setting must not add another row to T9", 20, h.keys().size)
            PinyinT9Keyboard.LetterGroups.forEach { (digit, letters) ->
                val key = h.textKey(letters)
                val position = digit - 1
                val rect = h.bounds(key)
                assertEquals(144, rect.width())
                assertEquals(105, rect.height())
                assertEquals(84 + (position % 3) * 144, rect.left)
                assertEquals((position / 3) * 105, rect.top)
                val hint = key.findViewWithTag<TextView>("t9-corner-digit")
                assertEquals(digit.toString(), hint.text)
                assertEquals(View.VISIBLE, hint.visibility)
                h.tap(key)
            }
            assertEquals((2..9).map { KeyAction.FcitxKeyAction(it.toString()) }, h.actions)
            assertFalse(h.actions.any { it is KeyAction.CommitAction || it is KeyAction.SymAction })
            h.tap(h.textKey("分词"))
            assertEquals(KeyAction.FcitxKeyAction("'"), h.actions.last())
            listOf("，", "。", "？", "！").forEach { punctuation ->
                h.tap(h.textKey(punctuation))
                assertEquals(KeyAction.CommitAction(punctuation), h.actions.last())
            }
            val all = h.keys().map(h::bounds)
            for (y in 0 until h.height) for (x in 0 until h.width) {
                assertEquals("Every pixel belongs to exactly one key at $x,$y", 1, all.count { it.contains(x, y) })
            }
        } finally { h.finish() }
    }

    @Test fun literalDigitsReturnAndLayoutSwitchesRetainTheirSeparateActions() {
        val h = Harness()
        try {
            h.touch(h.textKey("ABC"), MotionEvent.ACTION_DOWN)
            h.advance(320)
            h.touch(h.textKey("ABC"), MotionEvent.ACTION_UP)
            assertEquals(listOf(KeyAction.CommitAction("2")), h.actions)
            h.actions.clear()
            h.tap(h.textKey("26键"))
            h.tap(h.textKey("123"))
            h.tap(h.textKey("!#1"))
            h.tap(h.keyboard.lang)
            h.tap(h.keyboard.backspace)
            h.tap(h.keyboard.`return`)
            assertEquals(listOf(KeyAction.LayoutSwitchAction("Text26"), KeyAction.LayoutSwitchAction(NumberKeyboard.Name),
                KeyAction.LayoutSwitchAction(SymbolKeyboard.Name), KeyAction.LangSwitchAction,
                KeyAction.DeleteSelectionAction(0),
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)),
                KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))), h.actions)
            h.actions.clear()
            h.touch(h.keyboard.`return`, MotionEvent.ACTION_DOWN)
            h.advance(320)
            h.touch(h.keyboard.`return`, MotionEvent.ACTION_UP)
            assertEquals("Holding return inserts a newline instead of invoking Send", listOf(KeyAction.CommitAction("\n")), h.actions)
        } finally { h.finish() }
    }

    @Test fun spaceSupportsTapAndHeldFourDirectionCursorMovementWithoutAddingSpaces() {
        val h = Harness()
        try {
            h.tap(h.keyboard.space)
            assertEquals(listOf(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))), h.actions)
            h.actions.clear()
            h.touch(h.keyboard.space, MotionEvent.ACTION_DOWN)
            h.advance(320)
            h.touch(h.keyboard.space, MotionEvent.ACTION_MOVE, 20f, 0f)
            h.touch(h.keyboard.space, MotionEvent.ACTION_MOVE, 20f, -30f)
            h.touch(h.keyboard.space, MotionEvent.ACTION_MOVE, -10f, -30f)
            h.touch(h.keyboard.space, MotionEvent.ACTION_MOVE, -10f, 10f)
            h.touch(h.keyboard.space, MotionEvent.ACTION_UP, -10f, 10f)
            fun sym(key: Int) = KeyAction.SymAction(KeySym(key))
            assertEquals(listOf(KeyAction.SpaceLongPressAction) +
                List(2) { sym(FcitxKeyMapping.FcitxKey_Right) } + List(3) { sym(FcitxKeyMapping.FcitxKey_Up) } +
                List(3) { sym(FcitxKeyMapping.FcitxKey_Left) } + List(4) { sym(FcitxKeyMapping.FcitxKey_Down) }, h.actions)
        } finally { h.finish() }
    }

    @Test fun nativeT9RendersAtPhoneWidthAndSharesTheExistingPressEffect() {
        val h = Harness(360, 270, effects = true)
        try {
            assertEquals((360 * 0.48f).roundToInt(), h.keyboard.space.width)
            assertNotNull(h.keyboard.space.findViewWithTag<View>("space-key-icon"))
            h.capture("pinyin-t9-rest")
            val abc = h.textKey("ABC")
            h.touch(abc, MotionEvent.ACTION_DOWN)
            h.advance(45)
            assertNotNull("T9 keys use the normal per-key color route", PressEffect.colorForKey(abc.id))
            h.capture("pinyin-t9-pressed")
            h.touch(abc, MotionEvent.ACTION_UP)
            h.advance(250)
            h.capture("pinyin-t9-release")
        } finally { h.finish() }
    }

    @Test fun localKeyboardMenuOffersPinyinEnglishAndNumbersAndOnlyActsOnExplicitSelection() {
        val h = Harness()
        try {
            val selected = mutableListOf<KeyboardLayoutChoice>()
            val chooser = keyboardLayoutMenu(h.keyboard.context, h.keyboard.space) { selected += it }
            assertTrue(selected.isEmpty())
            assertEquals(listOf("拼音九键", "拼音26键", "英语", "数字"),
                (0 until chooser.menu.size()).map { chooser.menu.getItem(it).title.toString().replace(" ", "") })
            KeyboardLayoutChoice.entries.forEachIndexed { index, choice ->
                assertTrue(chooser.menu.performIdentifierAction(index + 1, 0))
                assertEquals(choice, selected.last())
            }
            assertEquals(KeyboardLayoutChoice.entries.toList(), selected)
            assertTrue("Opening and selecting a layout must not type any T9 digits", h.actions.isEmpty())
        } finally { h.finish() }
    }
}
