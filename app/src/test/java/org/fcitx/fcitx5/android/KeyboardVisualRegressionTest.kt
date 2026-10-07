package org.fcitx.fcitx5.android

import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import android.os.SystemClock
import android.view.Gravity
import org.fcitx.fcitx5.android.input.bar.RippleBarView
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import android.widget.TextView
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.LanguageKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
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
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.plusAssign
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardVisualRegressionTest {
    @Before
    fun prepareUiWithoutStartingTheNativeInputEngine() {
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, uiApplication)
        }
        AppPrefs.init(application.getSharedPreferences("keyboard-visual", Context.MODE_PRIVATE))
        // AppPrefs.init is idempotent: ThemeManager may still use another test's
        // existing backing store. Reset the actual store, not just this filename.
        val prefs = ThemeManager.prefs
        assertTrue(prefs.idleBreathing.sharedPreferences.edit().clear().commit())
        prefs.rippleShape.setValue(ThemePrefs.RippleShape.SoftMist)
        assertTrue("Every visual fixture must start with idle breathing enabled", prefs.idleBreathing.getValue())
    }

    private fun render(view: View): Bitmap {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        view.draw(canvas)
        return image
    }

    private fun save(image: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun keys(view: View): List<KeyView> = when (view) {
        is KeyView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test
    fun popupCommitsEnglishEllipsisAsTextAndKeepsSinglePunctuationAsKeys() {
        val context = RuntimeEnvironment.getApplication()
        fun trigger(text: String) = PopupKeyboardUi(
            context, ThemePreset.XuancaiBlackV09,
            Rect(0, 0, 600, 400), Rect(120, 300, 180, 360),
            radius = 12f, keyWidth = 44, keyHeight = 52, popupHeight = 100,
            keys = arrayOf(text), labels = arrayOf(text)
        ).onTrigger()
        assertEquals(KeyAction.CommitAction("..."), trigger("..."))
        assertEquals(KeyAction.FcitxKeyAction("，"), trigger("，"))
        assertEquals(KeyAction.FcitxKeyAction(","), trigger(","))
    }

    @Test
    fun realKeyboardBreathesBeforeAnyTouchAndFunctionKeysHaveNoPressedRectangle() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val root = FrameLayout(activity)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(root)
        root.addView(keyboard, FrameLayout.LayoutParams(600, 300))
        controller.visible()
        layout(root, 600, 300)
        val initial = render(keyboard)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4400))
        val glowing = render(keyboard)
        var changed = 0
        for (y in 0 until 300) for (x in 0 until 600) {
            if (initial.getPixel(x, y) != glowing.getPixel(x, y)) changed++
        }
        assertTrue("Idle must change actual keyboard pixels without pressing a key", changed > 20000)
        save(initial, "keyboard-idle-start")
        save(glowing, "keyboard-idle-bright")
        val functionKeys = keys(keyboard).filter {
            it.def.variant != KeyDef.Appearance.Variant.Normal || it.id == R.id.button_space
        }
        assertTrue(functionKeys.size >= 6)
        for ((index, key) in functionKeys.withIndex()) {
            key.isPressed = false
            val normal = render(key)
            key.isPressed = true
            key.jumpDrawablesToCurrentState()
            val pressed = render(key)
            assertTrue("Function key $index must not acquire a solid pressed rectangle", normal.sameAs(pressed))
            key.isPressed = false
        }
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun oldHighlightReproducesTheRectangleSoThePixelCheckIsSensitive() {
        val context = RuntimeEnvironment.getApplication()
        val oldTheme = ThemePreset.XuancaiBlackV09.copy(keyPressHighlightColor = 0x18ffffff)
        val key = TextKeyView(context, oldTheme,
            org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey(
                "!#1", org.fcitx.fcitx5.android.input.keyboard.SymbolKeyboard.Name
            ).appearance as KeyDef.Appearance.Text)
        layout(key, 90, 64)
        val normal = render(key)
        key.isPressed = true
        key.jumpDrawablesToCurrentState()
        val pressed = render(key)
        assertFalse(normal.sameAs(pressed))
        assertTrue(Color.red(pressed.getPixel(10, 10)) > Color.red(normal.getPixel(10, 10)))
    }
    private fun ime(english: Boolean, rimeAscii: Boolean = false) = InputMethodEntry(
        if (english && !rimeAscii) "keyboard-us" else "rime", "", "", "", "",
        if (english && !rimeAscii) "en" else "zh", if (rimeAscii) "rime" else "", false,
        if (rimeAscii) InputMethodSubMode("Latin Mode", "abc", "fcitx_rime_latin") else InputMethodSubMode())

    @Test
    fun languageKeyMatchesEngineEnglishUsesLowercaseAndShiftStillTypesUppercase() {
        val context = RuntimeEnvironment.getApplication()
        val keyboard = TextKeyboard(context, ThemePreset.XuancaiBlackV09)
        val q = keys(keyboard).filterIsInstance<TextKeyView>().first { (it.def as? KeyDef.Appearance.Text)?.displayText == "Q" }
        var action: KeyAction? = null
        keyboard.keyActionListener = KeyActionListener { value, _ -> action = value }
        keyboard.onInputMethodUpdate(ime(false))
        assertEquals("中", keyboard.lang.mainText.text.toString())
        assertEquals(TextKeyboard.spaceLabel(false), keyboard.space.mainText.text.toString())
        assertEquals("Q", q.mainText.text.toString())
        keyboard.lang.performClick()
        assertEquals(KeyAction.LangSwitchAction, action)
        keyboard.onInputMethodUpdate(ime(true))
        assertEquals("英", keyboard.lang.mainText.text.toString())
        assertEquals(TextKeyboard.spaceLabel(true), keyboard.space.mainText.text.toString())
        assertEquals("q", q.mainText.text.toString())
        q.performClick()
        assertEquals("q", (action as KeyAction.FcitxKeyAction).act)
        keyboard.caps.performClick()
        assertEquals("Q", q.mainText.text.toString())
        q.performClick()
        assertEquals("Q", (action as KeyAction.FcitxKeyAction).act)
        assertEquals("q", q.mainText.text.toString())
        keyboard.onInputMethodUpdate(ime(true, rimeAscii = true))
        assertEquals(TextKeyboard.spaceLabel(true), keyboard.space.mainText.text.toString())
        keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
        assertEquals(TextKeyboard.spaceLabel(true), keyboard.space.mainText.text.toString())
        val comma = keys(keyboard).filterIsInstance<TextKeyView>()
            .first { (it.def as? KeyDef.Appearance.Text)?.displayText == "," }
        assertEquals(",", comma.mainText.text.toString())
        keyboard.onInputMethodUpdate(ime(false))
        assertEquals("，", comma.mainText.text.toString())
        assertEquals(TextKeyboard.spaceLabel(false), keyboard.space.mainText.text.toString())
        keyboard.onDetach()
    }

    @Test
    fun samsungKeycapsHaveVisibleGapsButNoDeadTouchZonesAndSpaceUsesAnIcon() {
        val context = RuntimeEnvironment.getApplication()
        for (width in listOf(360, 800)) {
            val keyboard = TextKeyboard(context, ThemePreset.XuancaiBlackV09)
            layout(keyboard, width, 260)
            val row = keyboard.getChildAt(keyboard.childCount - 1) as ViewGroup
            val bottom = (0 until row.childCount).map { row.getChildAt(it) }.sortedBy { it.left }
            // Independent symbols/numpad entries, centered icon space, then language and enter.
            assertEquals(7, bottom.size)
            val weights = listOf(0.14f, 0.12f, 0.08f, 0.32f, 0.08f, 0.10f, 0.16f)
            bottom.forEachIndexed { index, key ->
                assertEquals("Bottom key $index must have its usable width", width * weights[index], key.width.toFloat(), 1f)
            }
            assertEquals(width * 0.32f, keyboard.space.width.toFloat(), 1f)
            assertEquals(width / 2f, (keyboard.space.left + keyboard.space.right) / 2f, 1f)
            assertTrue(keyboard.space.def is KeyDef.Appearance.Text)
            assertEquals("", keyboard.space.mainText.text.toString())
            assertNotNull(keyboard.space.findViewWithTag<View>("space-key-icon"))
            assertEquals(context.getString(R.string.space_key_label), keyboard.space.contentDescription)
            var lastAction: KeyAction? = null
            keyboard.keyActionListener = KeyActionListener { action, _ -> lastAction = action }
            bottom[0].performClick()
            assertEquals(KeyAction.LayoutSwitchAction(org.fcitx.fcitx5.android.input.keyboard.SymbolKeyboard.Name), lastAction)
            bottom[1].performClick()
            assertEquals(KeyAction.LayoutSwitchAction(org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard.Name), lastAction)
            assertSame(keyboard.lang, bottom[5])
            assertSame(keyboard.space, bottom[3])
            assertSame(keyboard.`return`, bottom[6])
            bottom[6].performClick()
            assertEquals(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)), lastAction)
            val image = render(keyboard)
            for (i in 0 until bottom.lastIndex) {
                assertEquals(bottom[i].right, bottom[i + 1].left)
                val x = bottom[i].right
                val cy = row.top + row.height / 2
                assertEquals(Color.BLACK, image.getPixel(x, cy))
                assertEquals(Color.BLACK, image.getPixel(x, cy - 10))
                val key = bottom[i] as KeyView
                assertEquals(3, key.hMargin)
                assertEquals(4, key.vMargin)
                assertTrue("A separate dark keycap must remain visible at rest",
                    Color.red(image.getPixel(key.left + key.hMargin + 5, cy - 8)) > 10)
            }
            val q = keys(keyboard).filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView>()
                .first { (it.def as KeyDef.Appearance.AltText).displayText == "Q" }
            assertEquals(View.GONE, q.altText.visibility)
            assertEquals("", q.altText.text.toString())
            assertEquals(5, keyboard.childCount)
            val digitRow = keyboard.getChildAt(0) as ViewGroup
            assertEquals("1234567890", (0 until digitRow.childCount).joinToString("") {
                (digitRow.getChildAt(it) as TextKeyView).mainText.text.toString()
            })
            keyboard.onDetach()
        }
    }

    @Test
    fun hidingLanguageKeepsSpaceCenteredAndTheWholeBottomRowTouchable() {
        val context = RuntimeEnvironment.getApplication()
        val showLanguage = AppPrefs.getInstance().keyboard.showLangSwitchKey
        val oldValue = showLanguage.getValue()
        try {
            for (width in listOf(360, 800)) {
                showLanguage.setValue(true)
                val keyboard = TextKeyboard(context, ThemePreset.XuancaiBlackV09)
                try {
                    for (visible in listOf(false, true)) {
                        showLanguage.setValue(visible)
                        layout(keyboard, width, 260)
                        val row = keyboard.getChildAt(keyboard.childCount - 1) as ViewGroup
                        val bottom = (0 until row.childCount).map { row.getChildAt(it) }
                            .filter { it.visibility == View.VISIBLE }.sortedBy { it.left }
                        assertEquals(if (visible) 7 else 6, bottom.size)
                        assertEquals(0, bottom.first().left)
                        assertEquals(width, bottom.last().right)
                        bottom.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left) }
                        assertEquals(width / 2f, (keyboard.space.left + keyboard.space.right) / 2f, 1f)
                        assertEquals(width * 0.32f, keyboard.space.width.toFloat(), 1f)
                        assertEquals(width * (if (visible) 0.16f else 0.26f), keyboard.`return`.width.toFloat(), 1f)
                    }
                } finally {
                    keyboard.onDetach()
                }
            }
        } finally {
            showLanguage.setValue(oldValue)
        }
    }

    @Test
    fun sendEditorShowsTheSwooshInTheLargerActualKeyAndKeepsReturnDispatch() {
        // Return now schedules a long press, just like the other lifecycle-aware keys.
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        val root = FrameLayout(activity)
        root.addView(keyboard, FrameLayout.LayoutParams(600, 300))
        activity.setContentView(root)
        controller.visible()
        val scope = DynamicScope()
        val actionIcon = ReturnKeyDrawableComponent()
        scope += InputBroadcaster()
        scope += actionIcon
        actionIcon.updateDrawableOnEditorInfo(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEND })
        actionIcon.updateDrawableOnPreedit(true)
        keyboard.onReturnDrawableUpdate(actionIcon.resourceId)
        layout(root, 600, 300)
        assertEquals(R.drawable.ic_send_swoosh_24, actionIcon.resourceId)
        assertEquals(96, keyboard.`return`.width)
        assertEquals(48, (keyboard.getChildAt(keyboard.childCount - 1) as ViewGroup).getChildAt(2).width)
        assertEquals(300f, (keyboard.space.left + keyboard.space.right) / 2f, 1f)
        save(render(keyboard), "v15-send-layout-rest")

        var action: KeyAction? = null
        keyboard.keyActionListener = KeyActionListener { actual, _ -> action = actual }
        val row = keyboard.getChildAt(keyboard.childCount - 1) as ViewGroup
        val x = (keyboard.`return`.left + keyboard.`return`.right) / 2f
        val y = row.top + row.height / 2f
        val downAt = SystemClock.uptimeMillis()
        MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).let {
            keyboard.dispatchTouchEvent(it)
            it.recycle()
        }
        save(render(keyboard), "v15-send-layout-press")
        MotionEvent.obtain(downAt, downAt + 50, MotionEvent.ACTION_UP, x, y, 0).let {
            keyboard.dispatchTouchEvent(it)
            it.recycle()
        }
        assertEquals(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)), action)
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun largeCandidatesScrollAndRealUiRendersBothLanguageStates() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val adapter = HorizontalCandidateViewAdapter(ThemePreset.XuancaiBlackV09)
        val words = arrayOf("你好", "您好", "你好吗", "你好呀", "你好世界", "泥好", "拟好", "倪好")
        adapter.updateCandidates(words.map { CandidateWord("", it, "") }.toTypedArray(), words.size)
        val manager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        val candidates = RecyclerView(activity).apply {
            layoutManager = manager
            this.adapter = adapter
            itemAnimator = null
        }
        val bar = CandidateUi(activity, ThemePreset.XuancaiBlackV09, candidates)
        bar.expandButton.visibility = View.VISIBLE
        val rippleBar = RippleBarView(activity).apply { setBackgroundColor(Color.BLACK) }
        rippleBar.addView(bar.root, FrameLayout.LayoutParams(360, 52))
        root.addView(rippleBar, FrameLayout.LayoutParams(360, 52))
        val glowOnBar = org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.pressGlowOnCandidates
        assertTrue("V12: the ripple reaches the candidate bar by default", glowOnBar.getValue())
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        root.addView(keyboard, FrameLayout.LayoutParams(360, 260).apply { topMargin = 52 })
        rippleBar.keyboard = keyboard
        controller.visible()
        layout(root, 360, 312)
        keyboard.onInputMethodUpdate(ime(false))
        keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
        layout(root, 360, 312)
        save(render(root), "layout-chinese")
        val barBefore = render(rippleBar)
        val downAt = SystemClock.uptimeMillis()
        // Rendering and Choreographer callbacks advance Robolectric's clock as well.
        // Inject exact frame times so the review clip shows the production envelope
        // at its real configured speed, independent of test-runner scheduling.
        val effect = ReflectionHelpers.getField<Any>(keyboard, "pressEffectLayer")
        var reviewTime = downAt
        val reviewClock: () -> Long = { reviewTime }
        ReflectionHelpers.setField(effect, "clock", reviewClock)
        MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, 170f, 80f, 0).let {
            keyboard.dispatchTouchEvent(it); it.recycle()
        }
        layout(root, 360, 312)
        save(render(root), "layout-key-ignite")
        MotionEvent.obtain(downAt, downAt + 30, MotionEvent.ACTION_UP, 170f, 80f, 0).let {
            keyboard.dispatchTouchEvent(it); it.recycle()
        }
        // Actual Android View frames, suitable for a timing review without a mock animation.
        for (frame in 0..54) {
            reviewTime = downAt + frame * 33L
            layout(root, 360, 312)
            save(render(root), "motion/frame-%03d".format(frame))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(33))
        }
        val liveClock: () -> Long = SystemClock::uptimeMillis
        ReflectionHelpers.setField(effect, "clock", liveClock)
        // Trigger another press to verify extension into the candidate strip.
        val secondAt = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(secondAt, secondAt, action, 170f, 80f, 0)
            keyboard.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        layout(root, 360, 312)
        val barAfter = render(rippleBar)
        assertFalse("With the option on, a key press must illuminate the candidate bar", barBefore.sameAs(barAfter))
        save(render(root), "layout-candidate-ripple")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1400))
        val first = candidates.findViewHolderForAdapterPosition(0)!!
        val label = (first.itemView as ViewGroup).getChildAt(0) as TextView
        assertEquals(24f, label.textSize / activity.resources.displayMetrics.scaledDensity, 0.01f)
        assertTrue(candidates.canScrollHorizontally(1))
        val startLeft = manager.getChildAt(0)!!.left
        // Send a drag through RecyclerView to cover touch interception and candidate child cancellation.
        for ((action, x, time) in listOf(Triple(MotionEvent.ACTION_DOWN, 280f, 0L),
                Triple(MotionEvent.ACTION_MOVE, 150f, 20L), Triple(MotionEvent.ACTION_MOVE, 30f, 40L),
                Triple(MotionEvent.ACTION_UP, 30f, 80L))) {
            val event = MotionEvent.obtain(0L, time, action, x, 26f, 0)
            candidates.dispatchTouchEvent(event)
            event.recycle()
        }
        assertTrue(manager.findFirstVisibleItemPosition() > 0 || manager.getChildAt(0)!!.left < startLeft)
        candidates.stopScroll()
        val visible = candidates.getChildViewHolder(candidates.getChildAt(0)) as org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
        assertEquals(words[visible.idx], visible.candidate.text)
        manager.scrollToPositionWithOffset(0, 0)
        layout(root, 360, 312)
        keyboard.onInputMethodUpdate(ime(true))
        keyboard.onPunctuationUpdate(emptyMap())
        layout(root, 360, 312)
        // The idle toolbar is shown for direct English entry: editing, undo, redo, clipboard, emoji.
        val toolbar = FrameLayout(activity)
        val buttons = ButtonsBarUi(activity, ThemePreset.XuancaiBlackV09)
        toolbar.addView(buttons.root, FrameLayout.LayoutParams(256, 52).apply { leftMargin = 52 })
        toolbar.addView(ToolButton(activity, R.drawable.ic_keyboard_tools_24, ThemePreset.XuancaiBlackV09),
            FrameLayout.LayoutParams(52, 52))
        toolbar.addView(ToolButton(activity, R.drawable.ic_baseline_keyboard_arrow_down_24, ThemePreset.XuancaiBlackV09),
            FrameLayout.LayoutParams(52, 52).apply { gravity = Gravity.RIGHT })
        rippleBar.addView(toolbar, FrameLayout.LayoutParams(360, 52))
        rippleBar.displayedChild = 1
        layout(root, 360, 312)
        save(render(root), "layout-english")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1400))
        layout(root, 360, 312)
        save(render(root), "layout-glow-sides")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1300))
        layout(root, 360, 312)
        save(render(root), "layout-glow-inward")
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun keyColourIsAboveItsBackgroundAndBelowTheWhiteCharacter() {
        val prefs = ThemeManager.prefs
        val previousPalette = prefs.pressEffectPalette.getValue()
        val previousCount = prefs.pressCustomColorCount.getValue()
        val previousColor = prefs.pressCustomColor1.getValue()
        val previousMotion = prefs.keyMotionEffect.getValue()
        val motionWasSaved = prefs.keyMotionEffect.sharedPreferences.contains(prefs.keyMotionEffect.key)
        prefs.pressEffectPalette.setValue(ThemePrefs.PressEffectPalette.Custom)
        prefs.pressCustomColorCount.setValue(1)
        prefs.pressCustomColor1.setValue(ThemePrefs.NeonColor.Cyan)
        // This test isolates background/face/glyph draw order using a fixed pixel
        // mask. Actual floating glyph transforms have their own native tests.
        prefs.keyMotionEffect.setValue(ThemePrefs.KeyMotionEffect.Off)
        try {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setTheme(R.style.Theme_InputViewTheme)
            val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(keyboard)
            controller.visible()
            layout(keyboard, 360, 260)
            val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "G" }
            val bounds = Rect()
            key.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(key, bounds)
            val effect = ReflectionHelpers.getField<Any>(keyboard, "pressEffectLayer")
            val now = SystemClock.uptimeMillis()
            val clock: () -> Long = { now }
            ReflectionHelpers.setField(effect, "clock", clock)
            val before = render(keyboard)
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            keyboard.dispatchTouchEvent(down)
            down.recycle()
            render(keyboard).recycle() // Draw the bright face without mutating the legend ink.
            val lit = render(keyboard)
            val face = lit.getPixel(bounds.left + 10, bounds.top + 10)
            assertTrue("The selected colour must paint above the black background",
                maxOf(Color.red(face), Color.green(face), Color.blue(face)) > 100)
            assertTrue("The complete cap is toned for clear white lettering without a black outline",
                ColorUtils.calculateLuminance(face) <= 0.18 && ColorUtils.calculateContrast(Color.WHITE, face) >= 4.5)
            var whitePixels = 0
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                val old = before.getPixel(x, y)
                if (minOf(Color.red(old), Color.green(old), Color.blue(old)) >= 242) {
                    whitePixels++
                    val pixel = lit.getPixel(x, y)
                    // Colour stays below the original light glyph, including on bright cyan.
                    assertTrue("The character must keep its light fill above the coloured face",
                        minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= 242)
                }
            }
            assertEquals("A coloured face must not invert its theme legend",
                ThemePreset.XuancaiBlackV09.keyTextColor, (key as TextKeyView).mainText.currentTextColor)
            assertTrue(whitePixels > 5)
            save(lit, "key-surface-before-character")
            keyboard.onDetach()
            controller.pause().stop().destroy()
        } finally {
            prefs.pressEffectPalette.setValue(previousPalette)
            prefs.pressCustomColorCount.setValue(previousCount)
            prefs.pressCustomColor1.setValue(previousColor)
            if (motionWasSaved) prefs.keyMotionEffect.setValue(previousMotion)
            else assertTrue(prefs.keyMotionEffect.sharedPreferences.edit().remove(prefs.keyMotionEffect.key).commit())
        }
    }

    @Test
    fun continuousTypingRendersRealKeyColoursPopupsAndRecordedRimeCandidates() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val theme = ThemePreset.XuancaiBlackV09
        val adapter = HorizontalCandidateViewAdapter(theme)
        val candidates = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
        }
        val bar = CandidateUi(activity, theme, candidates)
        val rippleBar = RippleBarView(activity).apply { setBackgroundColor(Color.BLACK) }
        rippleBar.addView(bar.root, FrameLayout.LayoutParams(360, 52))
        root.addView(rippleBar, FrameLayout.LayoutParams(360, 52))
        val keyboard = TextKeyboard(activity, theme)
        root.addView(keyboard, FrameLayout.LayoutParams(360, 260).apply { topMargin = 52 })
        rippleBar.keyboard = keyboard
        val popup = org.fcitx.fcitx5.android.input.popup.PopupEntryUi(activity, theme, 52, 12f)
        popup.root.visibility = View.INVISIBLE
        root.addView(popup.root, FrameLayout.LayoutParams(44, 100))
        val location = IntArray(2)
        keyboard.popupActionListener = org.fcitx.fcitx5.android.input.popup.PopupActionListener { action ->
            when (action) {
                is org.fcitx.fcitx5.android.input.popup.PopupAction.PreviewAction -> {
                    root.getLocationInWindow(location)
                    popup.setText(action.content)
                    popup.root.translationX = ((action.bounds.left + action.bounds.right - 44) / 2 - location[0])
                        .coerceIn(0, 316).toFloat()
                    popup.root.translationY = (action.bounds.bottom - 100 - 4 - location[1]).toFloat()
                    popup.root.visibility = View.VISIBLE
                }
                is org.fcitx.fcitx5.android.input.popup.PopupAction.DismissAction -> popup.root.visibility = View.INVISIBLE
                else -> {}
            }
        }
        // Captured from the same real librime/Lua/model with a fresh user dictionary.
        val recorded = javaClass.getResourceAsStream("/xuancai-typing-candidates.tsv")!!
            .bufferedReader().use { it.readLines().map { row -> row.split('\t') } }
        val input = "lianggehuangkimigcuiliao"
        assertEquals(input.length, recorded.size)
        val typed = StringBuilder()
        keyboard.keyActionListener = KeyActionListener { action, _ ->
            if (action is KeyAction.FcitxKeyAction) {
                typed.append(action.act)
                val row = recorded[typed.length - 1]
                assertEquals(row[0], typed.toString())
                val words = row.drop(1).map { CandidateWord("", it, "") }.toTypedArray()
                adapter.updateCandidates(words, words.size)
            }
        }
        controller.visible()
        layout(root, 360, 312)
        keyboard.onInputMethodUpdate(ime(false))
        val effect = ReflectionHelpers.getField<org.fcitx.fcitx5.android.input.keyboard.PressEffect>(keyboard, "pressEffectLayer")
        effect.prepareTexturesForTest()
        ReflectionHelpers.setField(effect, "random", kotlin.random.Random(1401))
        val start = SystemClock.uptimeMillis()
        var reviewTime = start
        val reviewClock: () -> Long = { reviewTime }
        ReflectionHelpers.setField(effect, "clock", reviewClock)
        save(render(root), "continuous/rest")
        val idleKeyboard = render(keyboard)
        val unusedLetters = keys(keyboard).filter { key ->
            val label = (key.def as? KeyDef.Appearance.Text)?.displayText.orEmpty()
            label.length == 1 && label[0].lowercaseChar() in 'a'..'z' && label.lowercase() !in input.map(Char::toString).toSet()
        }
        for (frame in 0..140) {
            reviewTime = start + frame * 33L
            layout(root, 360, 312)
            val index = frame / 4
            if (index < input.length && (frame % 4 == 0 || frame % 4 == 3)) {
                val label = input[index].uppercase()
                val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
                val bounds = Rect()
                key.getDrawingRect(bounds)
                keyboard.offsetDescendantRectToMyCoords(key, bounds)
                val action = if (frame % 4 == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                val event = MotionEvent.obtain(start + index * 132L, reviewTime, action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                keyboard.dispatchTouchEvent(event)
                event.recycle()
            }
            layout(root, 360, 312)
            val image = render(root)
            save(image, "continuous/typing-%03d".format(frame))
            if (frame == 0) {
                assertEquals(View.VISIBLE, popup.root.visibility)
                val first = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "L" }
                val bounds = Rect()
                first.getDrawingRect(bounds)
                root.offsetDescendantRectToMyCoords(first, bounds)
                val pixel = image.getPixel(bounds.left + 10, bounds.top + 10)
                assertTrue("The character preview must not hide the pressed key colour",
                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 190)
            }
            if (frame == 75) {
                val burst = render(keyboard)
                // Connected neon gaps may be bright. The resting cap interiors
                // and their white legends must remain dark and legible during input.
                var measuredCaps = 0
                for (key in unusedLetters) {
                    val rectangle = Rect()
                    key.getDrawingRect(rectangle)
                    keyboard.offsetDescendantRectToMyCoords(key, rectangle)
                    rectangle.inset(key.hMargin + 3, key.vMargin + 3)
                    var sum = 0L
                    var samples = 0
                    var readableInk = 0
                    for (y in rectangle.top until rectangle.bottom) for (x in rectangle.left until rectangle.right) {
                        val idlePixel = idleKeyboard.getPixel(x, y)
                        val maximum = maxOf(Color.red(idlePixel), Color.green(idlePixel), Color.blue(idlePixel))
                        val minimum = minOf(Color.red(idlePixel), Color.green(idlePixel), Color.blue(idlePixel))
                        val current = burst.getPixel(x, y)
                        if (maximum in 8..35 && maximum - minimum <= 12) {
                            sum += maxOf(Color.red(current), Color.green(current), Color.blue(current))
                            samples++
                        } else if (minimum >= 240 &&
                            minOf(Color.red(current), Color.green(current), Color.blue(current)) >= 230) {
                            readableInk++
                        }
                    }
                    if (samples > 20) {
                        measuredCaps++
                        assertTrue("Sustained neon input preserves a deep unpressed cap: ${sum.toDouble() / samples}",
                            sum.toDouble() / samples < 45.0)
                        assertTrue("The unpressed letter remains clearly visible above its dark cap", readableInk >= 3)
                    }
                }
                assertTrue("The brightness check must cover several real unpressed letter caps", measuredCaps >= 8)
                burst.recycle()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(33))
        }
        idleKeyboard.recycle()
        assertEquals(input, typed.toString())
        assertEquals("两个黄鹂鸣翠柳", recorded.last()[1])
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun holdingSpaceThenDraggingMovesCursorWithoutTypingASpace() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(keyboard)
        controller.visible()
        layout(keyboard, 360, 260)
        val actions = mutableListOf<KeyAction>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
        val space = keyboard.space
        val start = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, time: Long) {
            val bounds = Rect()
            space.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(space, bounds)
            val event = MotionEvent.obtain(start, time, action, x + bounds.left, bounds.exactCenterY(), 0)
            keyboard.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 45f, start)
        touch(MotionEvent.ACTION_MOVE, 47f, start + 50)
        assertTrue(actions.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        touch(MotionEvent.ACTION_MOVE, 79f, start + 380)
        touch(MotionEvent.ACTION_MOVE, 58f, start + 400)
        touch(MotionEvent.ACTION_UP, 58f, start + 420)
        assertTrue(actions.contains(KeyAction.SpaceLongPressAction))
        val directions = actions.filterIsInstance<KeyAction.SymAction>()
        assertTrue(directions.any { it.sym == org.fcitx.fcitx5.android.core.KeySym(FcitxKeyMapping.FcitxKey_Right) })
        assertTrue(directions.any { it.sym == org.fcitx.fcitx5.android.core.KeySym(FcitxKeyMapping.FcitxKey_Left) })
        assertFalse(directions.any { it.sym == org.fcitx.fcitx5.android.core.KeySym(FcitxKeyMapping.FcitxKey_space) })
        actions.clear()
        touch(MotionEvent.ACTION_DOWN, 45f, start + 450)
        touch(MotionEvent.ACTION_UP, 45f, start + 480)
        assertEquals(listOf(KeyAction.SymAction(org.fcitx.fcitx5.android.core.KeySym(FcitxKeyMapping.FcitxKey_space))), actions)
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun draggingAcrossLettersUpdatesPreviewAndOnlyCommitsTheReleaseKey() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(keyboard)
        controller.visible()
        layout(keyboard, 360, 260)
        keyboard.onInputMethodUpdate(ime(true))
        val actions = mutableListOf<KeyAction>()
        val previews = mutableListOf<String>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
        keyboard.popupActionListener = org.fcitx.fcitx5.android.input.popup.PopupActionListener { action ->
            if (action is org.fcitx.fcitx5.android.input.popup.PopupAction.PreviewAction) previews.add(action.content)
        }
        fun key(label: String) = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
        var touchTime = SystemClock.uptimeMillis()
        fun touch(action: Int, label: String) {
            val view = key(label)
            val rect = Rect()
            view.getDrawingRect(rect)
            keyboard.offsetDescendantRectToMyCoords(view, rect)
            val event = MotionEvent.obtain(0, touchTime, action, rect.exactCenterX(), rect.exactCenterY(), 0)
            keyboard.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, "G")
        touch(MotionEvent.ACTION_MOVE, "H")
        touchTime += 64
        touch(MotionEvent.ACTION_MOVE, "H")
        touch(MotionEvent.ACTION_MOVE, "J")
        assertTrue(actions.isEmpty())
        assertEquals(listOf("g", "h", "j"), previews)
        assertFalse(key("G").isPressed)
        assertTrue(key("J").isPressed)
        save(render(keyboard), "layout-slide-g-h-j")
        touch(MotionEvent.ACTION_UP, "J")
        assertEquals(listOf("j"), actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.act })
        actions.clear()
        touch(MotionEvent.ACTION_DOWN, "G")
        touch(MotionEvent.ACTION_MOVE, "T")
        touchTime += 64
        touch(MotionEvent.ACTION_MOVE, "T")
        touch(MotionEvent.ACTION_MOVE, "5")
        touch(MotionEvent.ACTION_UP, "5")
        assertEquals(listOf("5"), actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.act })
        actions.clear()
        touch(MotionEvent.ACTION_DOWN, "G")
        val cancel = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, -20f, -20f, 0)
        keyboard.dispatchTouchEvent(cancel)
        cancel.recycle()
        assertTrue(actions.isEmpty())
        assertFalse(key("G").isPressed)
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun symbolPageOpensWithPunctuationAndCommitsTheDisplayedSymbol() {
        val context = RuntimeEnvironment.getApplication()
        val items = org.fcitx.fcitx5.android.input.picker.PickerData.Symbol.first().second.take(28)
        assertTrue(items.none { it.length == 1 && it[0].isDigit() })
        assertTrue(items.containsAll(listOf("，", "。", "？", "！", "@", "#")))
        val ui = org.fcitx.fcitx5.android.input.picker.PickerPageUi(context, ThemePreset.XuancaiBlackV09,
            org.fcitx.fcitx5.android.input.picker.PickerPageUi.Density.High, true)
        ui.setItems(items)
        var last: KeyAction? = null
        ui.keyActionListener = KeyActionListener { action, _ -> last = action }
        layout(ui.root, 360, 156)
        val symbolKeys = keys(ui.root).filterIsInstance<TextKeyView>()
        symbolKeys.first { it.mainText.text == "，" }.performClick()
        assertEquals(KeyAction.CommitAction("，"), last)
        symbolKeys.first { it.mainText.text == "@" }.performClick()
        assertEquals(KeyAction.CommitAction("@"), last)
        save(render(ui.root), "layout-symbols-first-page")
    }

    @Test
    fun rapidTapsKeepEveryLetterDespiteSmallLiftOffDrift() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(keyboard)
        controller.visible()
        layout(keyboard, 360, 260)
        keyboard.onInputMethodUpdate(ime(true))
        val typed = StringBuilder()
        keyboard.keyActionListener = KeyActionListener { action, _ ->
            if (action is KeyAction.FcitxKeyAction) typed.append(action.act)
        }
        val input = "lianggehuangkimigcuiliao"
        var time = SystemClock.uptimeMillis()
        for (letter in input) {
            val key = keys(keyboard).first {
                (it.def as? KeyDef.Appearance.Text)?.displayText == letter.uppercase()
            }
            val bounds = Rect()
            key.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(key, bounds)
            val down = time
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to bounds.exactCenterX(),
                MotionEvent.ACTION_MOVE to bounds.right + 2f, MotionEvent.ACTION_UP to bounds.right + 2f)) {
                val event = MotionEvent.obtain(down, time, action, x, bounds.exactCenterY(), 0)
                keyboard.dispatchTouchEvent(event)
                event.recycle()
                time += 10
            }
        }
        assertEquals("The UI must pass the exact raw sequence to Rime, without lost or duplicated taps",
            input, typed.toString())
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun finalUpPreservesTheInitialLetterWithoutMoveAndCannotTriggerAFunctionKey() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(keyboard)
        controller.visible()
        layout(keyboard, 360, 260)
        val actions = mutableListOf<KeyAction>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
        fun touch(action: Int, key: KeyView) {
            val bounds = Rect()
            key.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(key, bounds)
            val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            keyboard.dispatchTouchEvent(event)
            event.recycle()
        }
        fun key(label: String) = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
        touch(MotionEvent.ACTION_DOWN, key("G"))
        touch(MotionEvent.ACTION_UP, key("H"))
        assertEquals(1, actions.size)
        assertEquals("An UP alone cannot turn a normal tap into a slide", "g",
            (actions.single() as KeyAction.FcitxKeyAction).act)
        val effect = ReflectionHelpers.getField<Any>(keyboard, "pressEffectLayer")
        val breathing = ReflectionHelpers.getField<Any>(effect, "breathing")
        assertFalse("Lift-off must release the breathing envelope",
            ReflectionHelpers.getField<Boolean>(breathing, "touching"))
        actions.clear()
        touch(MotionEvent.ACTION_DOWN, key("G"))
        touch(MotionEvent.ACTION_UP, keyboard.lang)
        assertTrue(actions.isEmpty())
        touch(MotionEvent.ACTION_DOWN, key("G"))
        val outside = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, -30f, -30f, 0)
        keyboard.dispatchTouchEvent(outside)
        outside.recycle()
        assertTrue(actions.isEmpty())
        assertTrue(keys(keyboard).none { it.isPressed })
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun twoThumbsKeepIndependentTargetsWhileOneFingerSlides() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(keyboard)
        controller.visible()
        layout(keyboard, 360, 260)
        val typed = mutableListOf<String>()
        keyboard.keyActionListener = KeyActionListener { action, _ ->
            if (action is KeyAction.FcitxKeyAction) typed.add(action.act)
        }
        var touchTime = SystemClock.uptimeMillis()
        fun event(action: Int, vararg pointers: Pair<Int, String>) {
            val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply {
                this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = pointers.map { (_, label) ->
                val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
                val bounds = Rect()
                key.getDrawingRect(bounds)
                keyboard.offsetDescendantRectToMyCoords(key, bounds)
                MotionEvent.PointerCoords().apply { x = bounds.exactCenterX(); y = bounds.exactCenterY(); pressure = 1f; size = 1f }
            }.toTypedArray()
            val e = MotionEvent.obtain(0, touchTime, action, pointers.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            keyboard.dispatchTouchEvent(e)
            e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 0 to "G")
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0 to "G", 1 to "H")
        event(MotionEvent.ACTION_MOVE, 0 to "T", 1 to "H")
        touchTime += 64
        event(MotionEvent.ACTION_MOVE, 0 to "T", 1 to "H")
        assertTrue(typed.isEmpty())
        event(MotionEvent.ACTION_POINTER_UP, 0 to "T", 1 to "H")
        event(MotionEvent.ACTION_UP, 1 to "H")
        assertEquals(listOf("t", "h"), typed)
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

}
