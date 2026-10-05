/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.*
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ChinesePunctuationTest {
    private lateinit var context: Context
    private lateinit var controller: ActivityController<ComponentActivity>
    private var oldApplication: Any? = null
    private val keyboards = mutableListOf<BaseKeyboard>()
    private val chinese = InputMethodEntry("rime", "Rime", "", "中州韵", "中", "zh_CN", "rime", true,
        InputMethodSubMode("rime_ice", "中", "fcitx_rime"))

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_InputViewTheme)
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        oldApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        field.set(null, app)
        AppPrefs.init(context.getSharedPreferences("chinese-punctuation-test", Context.MODE_PRIVATE))
        AppPrefs.getInstance().keyboard.popupOnKeyPress.setValue(true)
        AppPrefs.getInstance().keyboard.hapticStrength.setValue(0)
        AppPrefs.getInstance().advanced.disableAnimation.setValue(true)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).also {
            it.get().setTheme(R.style.Theme_InputViewTheme)
        }.setup().visible()
        context = controller.get()
    }

    @After fun finish() {
        keyboards.forEach { it.onDetach() }
        controller.pause().stop().destroy()
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, oldApplication)
        }
    }

    private fun <T : BaseKeyboard> layout(keyboard: T): T = keyboard.also {
        keyboards += it
        // The real key touch handler schedules long-press work in its view-tree
        // lifecycle; attach to an Activity just as the input view is in production.
        controller.get().setContentView(it)
        it.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY))
        it.layout(0, 0, 360, 270)
    }

    private fun keys(view: View): List<KeyView> = when (view) {
        is KeyView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun textKey(keyboard: BaseKeyboard, definition: String) = keys(keyboard)
        .filterIsInstance<TextKeyView>().first { (it.def as KeyDef.Appearance.Text).displayText == definition }

    private fun tap(keyboard: BaseKeyboard, key: View) {
        val bounds = Rect().also {
            key.getDrawingRect(it)
            keyboard.offsetDescendantRectToMyCoords(key, it)
        }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, now, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun chineseKeysPreviewsAndCommitsAgreeWithoutFcitxMappingOrWithStaleAsciiMapping() {
        val keyboard = layout(TextKeyboard(context, ThemePreset.XuancaiBlackV09))
        val actions = mutableListOf<KeyAction>()
        val previews = mutableListOf<String>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
        keyboard.popupActionListener = PopupActionListener { action ->
            if (action is PopupAction.PreviewAction) previews += action.content
        }
        keyboard.onInputMethodUpdate(chinese)
        for (mapping in listOf(emptyMap(), mapOf("." to ".", "," to ","), mapOf("." to "．"))) {
            keyboard.onPunctuationUpdate(mapping)
            for ((ascii, fullWidth) in listOf("." to "。", "," to "，")) {
                val key = textKey(keyboard, ascii)
                assertEquals(fullWidth, key.mainText.text.toString())
                tap(keyboard, key)
                assertEquals(fullWidth, previews.last())
                assertEquals(KeyAction.CommitAction(fullWidth), actions.last())
            }
        }
    }

    @Test fun languageChangesImmediatelyRestoreAsciiInEnglishAndChineseMarksOnReturn() {
        val keyboard = layout(TextKeyboard(context, ThemePreset.XuancaiBlackV09))
        val actions = mutableListOf<KeyAction>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
        val englishEngines = listOf(
            chinese.copy(uniqueName = "keyboard-us", languageCode = "en"),
            chinese.copy(subMode = InputMethodSubMode("rime_ice", "A", "fcitx_rime_latin")))
        for (english in englishEngines) {
            keyboard.onInputMethodUpdate(chinese)
            keyboard.onPunctuationUpdate(mapOf("." to "。", "," to "，"))
            keyboard.onInputMethodUpdate(english)
            for (ascii in listOf(".", ",")) {
                val key = textKey(keyboard, ascii)
                assertEquals(ascii, key.mainText.text.toString())
                tap(keyboard, key)
                assertEquals(KeyAction.FcitxKeyAction(ascii), actions.last())
            }
            keyboard.onPunctuationUpdate(emptyMap())
            keyboard.onInputMethodUpdate(chinese)
            val period = textKey(keyboard, ".")
            assertEquals("。", period.mainText.text.toString())
            tap(keyboard, period)
            assertEquals(KeyAction.CommitAction("。"), actions.last())
        }
    }

    @Test fun decimalKeyAndExplicitSymbolChoicesRemainLiteralInChineseMode() {
        val numeric = layout(NumberKeyboard(context, ThemePreset.XuancaiBlackV09))
        var action: KeyAction? = null
        val listener = KeyActionListener { value, _ -> action = value }
        numeric.keyActionListener = listener
        numeric.onInputMethodUpdate(chinese)
        val decimal = textKey(numeric, ".")
        assertEquals(".", decimal.mainText.text.toString())
        tap(numeric, decimal)
        assertEquals(KeyAction.SymAction(KeySym(0xffae), NumLockState), action)

        val symbols = layout(SymbolKeyboard(context, ThemePreset.XuancaiBlackV09,
            SymbolKeyboardState(SymbolCategory.English),
            SymbolHistory(context.getSharedPreferences("chinese-punctuation-symbols", Context.MODE_PRIVATE))))
        symbols.keyActionListener = listener
        symbols.onInputMethodUpdate(chinese)
        textKey(symbols, ".").performClick()
        assertEquals(KeyAction.CommitAction("."), action)

        val text = layout(TextKeyboard(context, ThemePreset.XuancaiBlackV09))
        text.keyActionListener = listener
        text.onInputMethodUpdate(chinese)
        ReflectionHelpers.callInstanceMethod<Void>(text, "onAction",
            ReflectionHelpers.ClassParameter.from(KeyAction::class.java, KeyAction.CommitAction(".")),
            ReflectionHelpers.ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Popup))
        assertEquals(KeyAction.CommitAction("."), action)
        ReflectionHelpers.callInstanceMethod<Void>(text, "onAction",
            ReflectionHelpers.ClassParameter.from(KeyAction::class.java, KeyAction.FcitxKeyAction(".")),
            ReflectionHelpers.ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Popup))
        assertEquals(KeyAction.FcitxKeyAction("."), action)
    }
}
