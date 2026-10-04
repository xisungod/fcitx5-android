package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
class SamsungSymbolKeyboardTest {
    private lateinit var context: Context
    private lateinit var history: SymbolHistory

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, app) }
        AppPrefs.init(context.getSharedPreferences("symbol-layout-test", Context.MODE_PRIVATE))
        val prefs = context.getSharedPreferences("symbol-history-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        history = SymbolHistory(prefs)
    }

    private fun keys(view: View): List<KeyView> = when (view) {
        is KeyView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 600, 420)
    }

    private fun textKey(view: View, text: String) = keys(view).filterIsInstance<TextKeyView>()
        .first { (it.def as KeyDef.Appearance.Text).displayText == text }

    @Test fun numericKeypadHasLargeThreeByThreeDigitsAndUsesRealKeypadSymbols() {
        val keyboard = NumberKeyboard(context, ThemePreset.XuancaiBlackV09)
        layout(keyboard)
        val actions = mutableListOf<KeyAction>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
        for (digit in 1..9) {
            val key = textKey(keyboard, digit.toString())
            assertEquals(144, key.width)
            assertEquals(105, key.height)
            assertEquals(84 + ((digit - 1) % 3) * 144, key.left)
            assertEquals(((digit - 1) / 3) * 105, key.top)
            // Dispatch genuine parent touches so the reflowed hit rectangles are exercised.
            val x = key.left + key.width / 2f
            val y = key.top + key.height / 2f
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { type ->
                MotionEvent.obtain(1, 2, type, x, y, 0).also { keyboard.dispatchTouchEvent(it); it.recycle() }
            }
        }
        textKey(keyboard, "0").performClick()
        assertEquals((1..9).map { KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(0xffb0 + it), NumLockState) } +
            KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(0xffb0), NumLockState), actions)
        textKey(keyboard, ".").performClick()
        assertEquals(KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(0xffae), NumLockState), actions.last())
        assertNotNull(keyboard.space.findViewWithTag<View>("space-key-icon"))
        assertTrue(NumberKeyboard.Layout.flatten().filterIsInstance<BackspaceKey>().single().behaviors.any { it is KeyDef.Behavior.Repeat })
        assertTrue(NumberKeyboard.Layout.flatten().filterIsInstance<SpaceKey>().single().behaviors.any {
            it is KeyDef.Behavior.LongPress && it.action == KeyAction.SpaceLongPressAction
        })
    }

    @Test fun categoryButtonsOnlyNavigateAndChineseEnglishSymbolsCommitLiterally() {
        val keyboard = SymbolKeyboard(context, ThemePreset.XuancaiBlackV09, SymbolKeyboardState(), history)
        var action: KeyAction? = null
        keyboard.keyActionListener = KeyActionListener { value, _ -> action = value }
        SymbolCategory.entries.forEach { category ->
            textKey(keyboard, category.label).performClick()
            assertEquals(KeyAction.LayoutSwitchAction(SymbolKeyboard.categoryRoute(category)), action)
        }
        listOf(SymbolCategory.Chinese to "，", SymbolCategory.English to ",").forEach { (category, symbol) ->
            val page = SymbolKeyboard(context, ThemePreset.XuancaiBlackV09, SymbolKeyboardState(category), history)
            page.keyActionListener = keyboard.keyActionListener
            textKey(page, symbol).performClick()
            assertEquals(KeyAction.CommitAction(symbol), action)
        }
    }

    @Test fun unlockedSelectionReturnsToLettersWhileLockedSelectionStaysForContinuousSymbols() {
        for (locked in listOf(true, false)) {
            val keyboard = SymbolKeyboard(context, ThemePreset.XuancaiBlackV09, SymbolKeyboardState(locked = locked), history)
            val actions = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
            textKey(keyboard, "？").performClick()
            assertEquals(if (locked) listOf(KeyAction.CommitAction("？")) else
                listOf(KeyAction.CommitAction("？"), KeyAction.LayoutSwitchAction(TextKeyboard.Name)), actions)
        }
    }

    @Test fun recentHistorySurvivesRecreationDeduplicatesAndStoresWholeStrings() {
        history.record("，")
        history.record("https://")
        history.record("😊")
        history.record("，")
        val recreated = SymbolHistory(context.getSharedPreferences("symbol-history-test", Context.MODE_PRIVATE))
        assertEquals(listOf("，", "😊", "https://"), recreated.items)
        val recent = SymbolKeyboardState().visibleItems(recreated.items)
        assertEquals(recreated.items, recent.take(3))
        assertEquals(1, recent.count { it == "，" })
        history.locked = false
        assertFalse(recreated.locked)
    }

    @Test fun pagingStopsAtEdgesAndSwitchingCategoryResetsItsPage() {
        var state = SymbolKeyboardState(SymbolCategory.Chinese)
        assertEquals(0, state.navigate(SymbolKeyboard.Previous, emptyList()).page)
        repeat(20) { state = state.navigate(SymbolKeyboard.Next, emptyList()) }
        assertEquals(state.pageCount(emptyList()) - 1, state.page)
        val keyboard = SymbolKeyboard(context, ThemePreset.XuancaiBlackV09, state, history)
        assertFalse(keys(keyboard).first { it.contentDescription == "下一页" }.isEnabled)
        state = state.navigate(SymbolKeyboard.categoryRoute(SymbolCategory.Internet), emptyList())
        assertEquals(0, state.page)
        assertEquals(SymbolCategory.Internet, state.category)
        assertTrue(state.visibleItems(emptyList()).contains(".com"))
        assertFalse(state.navigate(SymbolKeyboard.Lock, emptyList()).locked)
    }

    @Test fun independentCategoryRailAndGridFillKeyboardWithoutOverlappingTouchRects() {
        val keyboard = SymbolKeyboard(context, ThemePreset.XuancaiBlackV09, SymbolKeyboardState(), history)
        layout(keyboard)
        val all = keys(keyboard)
        assertEquals(26, all.size)
        assertTrue(all.take(5).all { it.width == 84 })
        assertTrue(all.drop(5).take(16).all { it.width == 129 })
        assertEquals(420, all.takeLast(5).first().bottom)
        for ((index, first) in all.withIndex()) for (second in all.drop(index + 1)) {
            assertFalse("Key touch rectangles must remain separate", first.left < second.right &&
                first.right > second.left && first.top < second.bottom && first.bottom > second.top)
        }
        // Every pixel belongs to exactly one key, including fractional row boundaries.
        for (y in 0 until keyboard.height) for (x in 0 until keyboard.width) {
            assertEquals("Uncovered or overlapping touch pixel ($x,$y)", 1,
                all.count { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
        }
    }

    @Test fun longNetworkLabelsFitTheirOwnCell() {
        val keyboard = SymbolKeyboard(context, ThemePreset.XuancaiBlackV09,
            SymbolKeyboardState(SymbolCategory.Internet), history)
        layout(keyboard)
        val long = textKey(keyboard, "https://")
        assertEquals(org.fcitx.fcitx5.android.input.AutoScaleTextView.Mode.Proportional, long.mainText.scaleMode)
        assertEquals(android.view.Gravity.CENTER, long.mainText.gravity)
        assertTrue(long.mainText.width <= long.width - 2 * long.hMargin)
        assertTrue(long.mainText.textSize < textKey(keyboard, "@").mainText.textSize)
    }
}
