/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.RippleBarView
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyPressDepth
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.SymbolCategory
import org.fcitx.fcitx5.android.input.keyboard.SymbolHistory
import org.fcitx.fcitx5.android.input.keyboard.SymbolKeyboard
import org.fcitx.fcitx5.android.input.keyboard.SymbolKeyboardState
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupEntryUi
import org.junit.Assert.*
import org.junit.After
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
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import splitties.dimensions.dp
import java.io.File
import java.time.Duration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** Review frames are drawn by the same Android views and touch path as the APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class V15PreviewRenderingTest {
    @Before
    fun prepareUiWithoutStartingTheNativeInputEngine() {
        // PAUSED looper alone does not pause native VSync in Robolectric 4.17.
        // Stop its automatic 15ms clock jumps before any activity is created.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, uiApplication)
        }
        val preferences = application.getSharedPreferences("v15-preview", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs.init(preferences)
        // AppPrefs.init is intentionally idempotent. Robolectric can reuse its already
        // registered ThemePrefs from another test class, backed by a different file.
        // Reset that actual file rather than leaving a previous palette/timing fixture active.
        ThemeManager.prefs.pressEffect.sharedPreferences.edit().clear().commit()
        assertEquals(ThemePrefs.PressColorMode.Random, ThemeManager.prefs.pressColorMode.getValue())
        assertEquals(ThemePrefs.PressEffectPalette.Cyberpunk, ThemeManager.prefs.pressEffectPalette.getValue())
    }

    @After
    fun restoreTheDefaultVsyncPolicy() {
        ShadowChoreographer.setPaused(false)
    }

    private fun advance(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0L) {
            val step = minOf(remaining, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    private fun keys(view: View): List<KeyView> = when (view) {
        is KeyView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun layout(view: View, width: Int = 600, height: Int = 420) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun render(view: View): Bitmap {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        view.draw(canvas)
        return image
    }

    private data class SurfaceFrame(val image: Bitmap, val transforms: Map<Int, Matrix>)

    /** Record the production Canvas transform without replacing its surface drawing. */
    private fun renderSurfaces(root: View, observedKeys: Collection<KeyView>): SurfaceFrame {
        val originals = observedKeys.associateWith { it.keySurfacePainter }
        val transforms = mutableMapOf<Int, Matrix>()
        try {
            originals.forEach { (key, painter) ->
                key.keySurfacePainter = KeyView.KeySurfacePainter { canvas, width, height ->
                    @Suppress("DEPRECATION")
                    val transform = Matrix(canvas.matrix)
                    transforms[key.id] = transform
                    painter?.draw(canvas, width, height)
                }
            }
            val image = render(root)
            assertEquals("Every observed key must execute its actual appearance draw", originals.size, transforms.size)
            return SurfaceFrame(image, transforms)
        } finally {
            originals.forEach { (key, painter) -> key.keySurfacePainter = painter }
        }
    }

    private fun SurfaceFrame.colourAtRestPoint(key: KeyView, root: View, x: Float, y: Float): Int {
        val appearance = ReflectionHelpers.getField<View>(key, "appearanceView")
        val origin = screenBoundsIn(appearance, root)!!
        val point = floatArrayOf(x - origin.left, y - origin.top)
        transforms.getValue(key.id).mapPoints(point)
        val drawnX = point[0].roundToInt()
        val drawnY = point[1].roundToInt()
        assertTrue("The transformed colour sample must remain inside the real frame",
            drawnX in 0 until image.width && drawnY in 0 until image.height)
        return image.getPixel(drawnX, drawnY)
    }

    private fun faceOrMotionActive(key: KeyView): Boolean =
        key.floatingFaceOpacity() > 0f ||
            ReflectionHelpers.getField<KeyPressDepth>(key, "pressDepth").isTransitioning()

    private fun save(image: Bitmap, path: String) {
        val file = File("build/outputs/effect-checks/$path.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bounds(keyboard: BaseKeyboard, key: KeyView): Rect = Rect().also {
        key.getDrawingRect(it)
        keyboard.offsetDescendantRectToMyCoords(key, it)
    }

    private fun english(english: Boolean) = InputMethodEntry(
        if (english) "keyboard-us" else "rime", "", "", "", "",
        if (english) "en" else "zh", "", false, InputMethodSubMode())

    private fun screenBoundsIn(view: View, root: View): Rect? {
        if (!view.isShown) return null
        val rectangle = Rect()
        if (!view.getGlobalVisibleRect(rectangle)) return null
        val rootRectangle = Rect()
        if (!root.getGlobalVisibleRect(rootRectangle)) return null
        rectangle.offset(-rootRectangle.left, -rootRectangle.top)
        return rectangle
    }

    private fun exclude(mask: BooleanArray, rectangle: Rect, width: Int, height: Int) {
        for (y in rectangle.top.coerceAtLeast(0) until rectangle.bottom.coerceAtMost(height)) {
            for (x in rectangle.left.coerceAtLeast(0) until rectangle.right.coerceAtMost(width)) {
                mask[y * width + x] = false
            }
        }
    }

    private data class LightArea(
        val pixels: Int,
        val meanMaxRgb: Double,
        val peak: Int,
        val colour6: Int,
        val colour30: Int,
        val colour60: Int,
        val centreX: Double,
        val centreY: Double
    ) {
        val fraction30 get() = colour30.toDouble() / pixels.coerceAtLeast(1)
        val fraction6 get() = colour6.toDouble() / pixels.coerceAtLeast(1)
    }

    /** White glyphs and neutral highlights cannot satisfy the coloured-light thresholds. */
    private fun lightArea(pixels: IntArray, mask: BooleanArray, width: Int): LightArea {
        var count = 0
        var sum = 0L
        var peak = 0
        var colour6 = 0
        var colour30 = 0
        var colour60 = 0
        var weights = 0L
        var weightedX = 0L
        var weightedY = 0L
        for (index in pixels.indices) {
            if (!mask[index]) continue
            val pixel = pixels[index]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val maximum = maxOf(red, green, blue)
            val chroma = maximum - minOf(red, green, blue)
            count++
            sum += maximum
            peak = maxOf(peak, maximum)
            if (maximum > 6 && chroma >= 4) colour6++
            if (maximum > 30 && chroma >= 15) {
                colour30++
                if (maximum > 60) colour60++
                val weight = maximum - 30L
                weights += weight
                weightedX += (index % width) * weight
                weightedY += (index / width) * weight
            }
        }
        return LightArea(count, sum.toDouble() / count.coerceAtLeast(1), peak, colour6, colour30, colour60,
            if (weights > 0) weightedX.toDouble() / weights else -1.0,
            if (weights > 0) weightedY.toDouble() / weights else -1.0)
    }

    private data class VisibilityFrame(
        val frame: Int,
        val gap: LightArea,
        val candidate: LightArea,
        val fixedGap: LightArea
    ) {
        fun csv() = String.format(Locale.US,
            "%d,%d,%d,%.2f,%d,%d,%d,%.2f,%.2f,%d,%.2f,%d,%d,%d,%.2f",
            frame, frame * 50, gap.pixels, gap.meanMaxRgb, gap.peak, gap.colour30, gap.colour60,
            gap.centreX, gap.centreY, candidate.pixels, candidate.meanMaxRgb, candidate.peak,
            candidate.colour30, candidate.colour60, fixedGap.meanMaxRgb)
    }

    private fun longestRun(samples: List<VisibilityFrame>, condition: (VisibilityFrame) -> Boolean): Int {
        var longest = 0
        var current = 0
        for (sample in samples) {
            current = if (condition(sample)) current + 1 else 0
            longest = maxOf(longest, current)
        }
        return longest
    }

    private data class EffectTiming(
        val ignition: Long,
        val expansion: Long,
        val hold: Long,
        val fade: Long,
        val keyHold: Long,
        val keyRetreat: Long
    ) {
        val waveTotal get() = ignition + expansion + hold + fade
        val releasedKeyTotal get() = maxOf(50L, keyHold) + keyRetreat
        val completeTail get() = maxOf(waveTotal, releasedKeyTotal)
        fun provenance() = "ignition_ms=$ignition\nexpansion_ms=$expansion\nwave_hold_ms=$hold\n" +
            "fade_ms=$fade\nwave_total_ms=$waveTotal\nkey_hold_ms=$keyHold\nkey_retreat_ms=$keyRetreat\n"
    }

    private fun defaultSoftTiming(): EffectTiming {
        val prefs = ThemeManager.prefs
        val timing = EffectTiming(prefs.pressIgnitionTime.getValue().toLong(),
            prefs.pressExpansionTime.getValue().toLong(), prefs.pressWaveHoldTime.getValue().toLong(),
            prefs.pressFadeOutTime.getValue().toLong(), prefs.pressKeyHoldTime.getValue().toLong(),
            prefs.pressKeyRetreatTime.getValue().toLong())
        assertEquals("Use the APK's current extended default wave tail", 1380L, timing.waveTotal)
        assertEquals("The default initial feedback remains immediate", 40L, timing.ignition)
        assertEquals(400L, timing.expansion)
        assertEquals(40L, timing.hold)
        assertEquals(900L, timing.fade)
        assertEquals(50L, timing.keyHold)
        assertEquals(100L, timing.keyRetreat)
        return timing
    }

    /** Same reusable UI and lifecycle as PopupComponent, without its engine dependencies. */
    private inner class PreviewHost(private val root: FrameLayout) {
        private val entries = mutableMapOf<Int, PopupEntryUi>()
        private val free = ArrayDeque<PopupEntryUi>()
        private val rootLocation = IntArray(2)
        private val popupWidth = root.dp(44)
        private val popupHeight = root.dp(100)
        private val popupKeyHeight = root.dp(52)
        private val popupRadius = root.dp(12f)
        private val keyBottomMargin = root.dp(ThemeManager.prefs.keyVerticalMargin.getValue())

        init {
            repeat(3) { free.add(create()) }
        }

        private fun create() = PopupEntryUi(root.context, ThemePreset.XuancaiBlackV09, popupKeyHeight, popupRadius).also {
            it.root.visibility = View.INVISIBLE
            root.addView(it.root, FrameLayout.LayoutParams(popupWidth, popupHeight))
        }

        val listener = PopupActionListener { action ->
            when (action) {
                is PopupAction.PreviewAction -> {
                    val entry = entries[action.viewId] ?: (free.removeFirstOrNull() ?: create())
                    root.getLocationInWindow(rootLocation)
                    entry.setText(action.content)
                    entry.tint(PressEffect.colorForKey(action.viewId))
                    entry.root.translationX = ((action.bounds.left + action.bounds.right - popupWidth) / 2 - rootLocation[0])
                        .coerceIn(0, (root.width - popupWidth).coerceAtLeast(0)).toFloat()
                    entry.root.translationY = (action.bounds.bottom - popupHeight - keyBottomMargin - rootLocation[1]).toFloat()
                    entries[action.viewId] = entry
                    entry.previewLifecycle.show()
                }
                is PopupAction.PreviewUpdateAction -> entries[action.viewId]?.setText(action.content)
                is PopupAction.DismissAction -> entries[action.viewId]?.let { entry ->
                    entry.previewLifecycle.release {
                        if (entries[action.viewId] === entry) {
                            entries.remove(action.viewId)
                            free.add(entry)
                        }
                    }
                }
                else -> {}
            }
        }

        fun entry(viewId: Int) = entries[viewId]

        fun assertUnscaled() {
            entries.values.filter { it.root.visibility == View.VISIBLE && it.root.alpha > 0f }.forEach {
                assertEquals("The popup keeps its full size throughout its alpha lifecycle", 1f, it.root.scaleX, 0f)
                assertEquals("The popup keeps its full size throughout its alpha lifecycle", 1f, it.root.scaleY, 0f)
                assertEquals("The popup glyph is never animated in size", 1f, it.textView.scaleX, 0f)
                assertEquals("The popup glyph is never animated in size", 1f, it.textView.scaleY, 0f)
            }
        }

        fun visibleCharacterBounds(): List<Rect> = entries.values.mapNotNull {
            if (it.root.alpha > 0f) screenBoundsIn(it.textView, root) else null
        }

        fun stop() {
            entries.values.forEach { it.previewLifecycle.hideImmediately() }
            free.forEach { it.previewLifecycle.hideImmediately() }
        }
    }

    @Test
    fun continuousTypingUsesRealTouchesKeyFacesCandidateExtensionAndPopupLifecycle() {
        val timing = defaultSoftTiming()
        // Isolate the press-wave tail from idle breathing, which has a separate envelope.
        ThemeManager.prefs.idleBreathing.setValue(false)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val theme = ThemePreset.XuancaiBlackV09
        assertTrue(ThemeManager.prefs.pressEffect.getValue())
        assertTrue(ThemeManager.prefs.coloredPreview.getValue())
        assertTrue(ThemeManager.prefs.previewSameColor.getValue())
        assertTrue(ThemeManager.prefs.portraitNumberRow.getValue())
        assertEquals(ThemePrefs.KeyExitStyle.Dim, ThemeManager.prefs.keyExitStyle.getValue())
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val adapter = HorizontalCandidateViewAdapter(theme)
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
        }
        val candidates = CandidateUi(activity, theme, list)
        val bar = RippleBarView(activity).apply { setBackgroundColor(Color.BLACK) }
        bar.addView(candidates.root, FrameLayout.LayoutParams(600, 60))
        root.addView(bar, FrameLayout.LayoutParams(600, 60))
        val keyboard = TextKeyboard(activity, theme)
        root.addView(keyboard, FrameLayout.LayoutParams(600, 360).apply { topMargin = 60 })
        bar.keyboard = keyboard
        val previews = PreviewHost(root)
        keyboard.popupActionListener = previews.listener
        controller.visible()
        advance(32) // Deliver window traversal/attach while native VSync is explicitly paused.
        layout(root)
        assertTrue("The preview must use a genuinely attached, shown keyboard",
            keyboard.isAttachedToWindow && keyboard.isShown)
        keyboard.onInputMethodUpdate(english(false))
        keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。"))
        val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
        effect.prepareTexturesForTest()
        // Fix only this review/test's random stream; the APK continues to choose random colours.
        ReflectionHelpers.setField(effect, "random", Random(1401))
        var reviewTime = SystemClock.uptimeMillis()
        ReflectionHelpers.setField(effect, "clock", { reviewTime } as () -> Long)
        val start = reviewTime
        val space = bounds(keyboard, keyboard.space)
        assertEquals("The space key must remain centred", 300f, space.exactCenterX(), 1f)
        assertEquals("Space has an icon rather than a text label", "", keyboard.space.mainText.text.toString())
        assertNotNull("The real space icon must be attached to its key",
            keyboard.space.findViewWithTag<View>("space-key-icon"))
        val bottom = keys(keyboard).filter { bounds(keyboard, it).centerY() == space.centerY() }
            .sortedBy { bounds(keyboard, it).left }
        assertEquals(7, bottom.size)
        assertEquals("!#1", (bottom[0].def as KeyDef.Appearance.Text).displayText)
        assertEquals("123", (bottom[1].def as KeyDef.Appearance.Text).displayText)
        var route: KeyAction? = null
        keyboard.keyActionListener = KeyActionListener { action, _ -> route = action }
        bottom[0].performClick()
        assertEquals(KeyAction.LayoutSwitchAction(SymbolKeyboard.Name), route)
        bottom[1].performClick()
        assertEquals(KeyAction.LayoutSwitchAction(NumberKeyboard.Name), route)
        val idle = render(root)
        save(idle, "v15-text-rest")
        val untouched = bounds(keyboard, keys(keyboard).first {
            (it.def as? KeyDef.Appearance.Text)?.displayText == "G"
        })
        val untouchedPixel = idle.getPixel(untouched.left + 10, untouched.top + 60 + 12)
        assertTrue("At rest, normal keys retain their dark background",
            maxOf(Color.red(untouchedPixel), Color.green(untouchedPixel), Color.blue(untouchedPixel)) < 90)
        assertTrue("The resting keycap remains visible on the black base",
            maxOf(Color.red(untouchedPixel), Color.green(untouchedPixel), Color.blue(untouchedPixel)) > 10)

        // Candidate values were captured from the bundled real Rime engine; this UI test replays
        // them to exercise real candidate layout without loading an ARM native engine on the host.
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
        var readableGlyphPixels = 0
        val keyboardRectangle = screenBoundsIn(keyboard, root)!!
        val barRectangle = screenBoundsIn(bar, root)!!
        assertEquals(Rect(0, 60, root.width, root.height), keyboardRectangle)
        assertEquals(Rect(0, 0, root.width, 60), barRectangle)
        val idlePixels = IntArray(root.width * root.height).also {
            idle.getPixels(it, 0, root.width, 0, 0, root.width, root.height)
        }
        // Only the baseline's black keyboard gaps are eligible: key backgrounds,
        // letters, icons, and shadows do not count as light propagating outside a key.
        val baseGapMask = BooleanArray(idlePixels.size) { pixel ->
            keyboardRectangle.contains(pixel % root.width, pixel / root.width) &&
                maxOf(Color.red(idlePixels[pixel]), Color.green(idlePixels[pixel]), Color.blue(idlePixels[pixel])) <= 3
        }
        // The top 1/8 of the actual candidate bar is outside its 24sp glyph ink.
        // Current popup bounds are subtracted again before measuring every frame.
        val candidateMask = BooleanArray(idlePixels.size) { pixel ->
            val y = pixel / root.width
            barRectangle.contains(pixel % root.width, y) && y < barRectangle.top + barRectangle.height() / 8
        }
        val renderedKeys = keys(keyboard).associateBy { it.id }
        val keyRectangles = renderedKeys.mapValues { screenBoundsIn(it.value, root)!! }
        // Measure the actual dark interiors of letter caps separately from their
        // luminous gaps. Bright gaps are intentional; inactive caps must stay deep.
        val capMasks = keys(keyboard).filter {
            val label = (it.def as? KeyDef.Appearance.Text)?.displayText.orEmpty()
            label.length == 1 && label[0].lowercaseChar() in 'a'..'z'
        }.associate { key ->
            val rectangle = Rect(keyRectangles.getValue(key.id)).apply {
                inset(key.hMargin + 3, key.vMargin + 3)
            }
            key.id to BooleanArray(idlePixels.size) { pixel ->
                val c = idlePixels[pixel]
                val maximum = maxOf(Color.red(c), Color.green(c), Color.blue(c))
                val minimum = minOf(Color.red(c), Color.green(c), Color.blue(c))
                rectangle.contains(pixel % root.width, pixel / root.width) &&
                    maximum in 8..35 && maximum - minimum <= 12
            }
        }
        val inactiveCaps = ArrayList<LightArea>()
        val lastPressTimes = mutableMapOf<Int, Long>()
        val faceTail = timing.releasedKeyTotal
        // A fixed, conservative mask enables before/after-stop comparisons without
        // gaining newly eligible pixels merely because an old key face has disappeared.
        val fixedGapMask = baseGapMask.copyOf()
        val inputLetters = input.map(Char::toString).toSet()
        keys(keyboard).filter { (it.def as? KeyDef.Appearance.Text)?.displayText?.lowercase() in inputLetters }
            .forEach { key ->
                val rectangle = keyRectangles.getValue(key.id)
                exclude(fixedGapMask, rectangle, root.width, root.height)
                val previewRect = Rect(
                    ((rectangle.left + rectangle.right - root.dp(44)) / 2).coerceIn(0, root.width - root.dp(44)),
                    rectangle.bottom - root.dp(100) - root.dp(ThemeManager.prefs.keyVerticalMargin.getValue()),
                    0, 0)
                previewRect.right = previewRect.left + root.dp(44)
                previewRect.bottom = previewRect.top + root.dp(52)
                exclude(fixedGapMask, previewRect, root.width, root.height)
            }
        val visibility = ArrayList<VisibilityFrame>()
        val lastDown = (input.length - 1) * 150L
        val lastUp = lastDown + 50L
        // Preserve real 20fps time: retain the complete configured wave, then 450ms of black rest.
        val finalFrame = ((lastDown + timing.completeTail + 450L + 49L) / 50L).toInt()
        for (frame in 0..finalFrame) {
            reviewTime = start + frame * 50L
            layout(root)
            val index = frame / 3
            if (index < input.length && frame % 3 < 2) {
                val key = keys(keyboard).first {
                    (it.def as? KeyDef.Appearance.Text)?.displayText == input[index].uppercase()
                }
                val rect = bounds(keyboard, key)
                val action = if (frame % 3 == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                if (action == MotionEvent.ACTION_DOWN) lastPressTimes[key.id] = reviewTime
                val event = MotionEvent.obtain(start + index * 150L, reviewTime, action,
                    rect.exactCenterX(), rect.exactCenterY(), 0)
                assertTrue(keyboard.dispatchTouchEvent(event))
                event.recycle()
                if (frame == 0) {
                    val popup = previews.entry(key.id)!!
                    assertEquals(View.VISIBLE, popup.root.visibility)
                    assertEquals("l", popup.textView.text.toString())
                    assertEquals(PressEffect.colorForKey(key.id),
                        (popup.textView.background as GradientDrawable).color?.defaultColor)
                }
            }
            layout(root)
            render(root).recycle() // The real legend changes ink in response to the new face light.
            val firstKey = if (frame == 0) renderedKeys.values.first {
                (it.def as? KeyDef.Appearance.Text)?.displayText == "L"
            } else null
            val surfaceFrame = firstKey?.let { renderSurfaces(root, listOf(it)) }
            val image = surfaceFrame?.image ?: render(root)
            save(image, "v15-soft-typing/frame-%03d".format(frame))
            if (frame == 0) {
                val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "L" }
                val rect = bounds(keyboard, key).apply { offset(0, 60) }
                val face = surfaceFrame!!.colourAtRestPoint(key, root,
                    rect.left + 10f, rect.bottom - 15f)
                assertTrue("The real preview must leave the current key face visible",
                    maxOf(Color.red(face), Color.green(face), Color.blue(face)) > 100)
                for (y in rect.centerY() - 12..rect.centerY() + 12) for (x in rect.left until rect.right) {
                    val old = idle.getPixel(x, y)
                    if (minOf(Color.red(old), Color.green(old), Color.blue(old)) >= 242) {
                        val pixel = image.getPixel(x, y)
                        val contrast = abs(Color.red(pixel) - Color.red(face)) +
                            abs(Color.green(pixel) - Color.green(face)) + abs(Color.blue(pixel) - Color.blue(face))
                        if (contrast > 75) readableGlyphPixels++
                    }
                }
                assertTrue("The character stays legible above the coloured face", readableGlyphPixels > 5)
            }
            val gapMask = baseGapMask.copyOf()
            val candidateBackgroundMask = candidateMask.copyOf()
            // Exclude the full fixed touch cell while its real coloured cap floats.
            // The legacy 100ms preference no longer describes that cap's lifetime.
            for ((id, pressedAt) in lastPressTimes) {
                if (reviewTime - pressedAt <= faceTail || faceOrMotionActive(renderedKeys.getValue(id))) {
                    exclude(gapMask, keyRectangles.getValue(id), root.width, root.height)
                }
            }
            for (rectangle in previews.visibleCharacterBounds()) {
                exclude(gapMask, rectangle, root.width, root.height)
                exclude(candidateBackgroundMask, rectangle, root.width, root.height)
            }
            val pixels = IntArray(idlePixels.size)
            image.getPixels(pixels, 0, root.width, 0, 0, root.width, root.height)
            for ((id, capMask) in capMasks) {
                val pressedAt = lastPressTimes[id]
                // A recovering floating face is still active. Only fully resting
                // interiors may be counted as inactive black caps.
                if (pressedAt != null && (reviewTime - pressedAt <= faceTail || faceOrMotionActive(renderedKeys.getValue(id)))) continue
                val clearCap = capMask.copyOf()
                for (rectangle in previews.visibleCharacterBounds()) {
                    exclude(clearCap, rectangle, root.width, root.height)
                }
                val area = lightArea(pixels, clearCap, root.width)
                if (area.pixels >= 20) inactiveCaps.add(area)
            }
            visibility.add(VisibilityFrame(frame, lightArea(pixels, gapMask, root.width),
                lightArea(pixels, candidateBackgroundMask, root.width), lightArea(pixels, fixedGapMask, root.width)))
            image.recycle()
            // Advance only a bounded interval; never run a perpetually scheduled light to idle.
            advance(50)
        }
        assertEquals(input, typed.toString())
        assertEquals("两个黄鹂鸣翠柳", adapter.candidates.first().text)
        val active = visibility.filter { it.frame * 50L in 300L..lastUp }
        val tail = visibility.filter { it.frame * 50L > lastUp }
        val summary = String.format(Locale.US,
            "seed=1401\nactive_gap_colour60_peak_area=%d\nactive_gap_colour60_visible_frames=%d\n" +
                "active_candidate_colour30_longest_run=%d\nactive_fixed_gap_peak_mean=%.2f\ntail_fixed_gap_peak_mean=%.2f\n",
            active.maxOf { it.gap.colour60 }, active.count { it.gap.colour60 >= 750 },
            longestRun(active) { it.candidate.fraction30 >= 0.15 },
            active.maxOf { it.fixedGap.meanMaxRgb }, tail.maxOf { it.fixedGap.meanMaxRgb })
        val csv = "frame,time_ms,gap_pixels,gap_mean_maxrgb,gap_peak,gap_colour_gt30,gap_colour_gt60," +
            "gap_centre_x,gap_centre_y,candidate_strip_pixels,candidate_mean_maxrgb,candidate_peak," +
            "candidate_colour_gt30,candidate_colour_gt60,fixed_gap_mean_maxrgb\n" +
            visibility.joinToString("\n") { it.csv() } + "\n"
        File("build/outputs/effect-checks/v15-soft-typing/visibility.csv").writeText(csv)
        File("build/outputs/effect-checks/v15-soft-typing/preview.txt").writeText(
            "source=Android TextKeyboard/CandidateUi/RippleBarView/PopupEntryUi\n" +
                "graphics=Robolectric NATIVE\nframes=${finalFrame + 1}\nfps=20\nlast_frame_ms=${finalFrame * 50}\n" +
                "width=600\nheight=420\ndensity=hdpi\nlogical_width_dp=400\nidle_breathing=false (isolated press-wave review)\n" +
                "colour_mode=${ThemeManager.prefs.pressColorMode.getValue()}\n" +
                "random_palette=${ThemeManager.prefs.pressEffectPalette.getValue()}\n" +
                timing.provenance() +
                "key_interval_ms=150\ntouch_hold_ms=50\ninput=$input\n" +
                "candidates=replay of real bundled Rime fixture; not live native execution in this UI test\n" +
                "measurement=actual KeyView bounds; baseline black gaps; active key cells and popup glyph bounds excluded\n" +
                "coloured_pixel=maxRGB>30/60 and channel spread>=15\n" + summary + "\n" + csv)
        assertTrue("A moving light field must cover a visible area outside active key faces: $summary",
            active.maxOf { it.gap.colour60 } >= 1500)
        assertTrue("Rapid input must retain a visible external light field over multiple frames: $summary",
            active.count { it.gap.colour60 >= 750 } >= 18)
        assertTrue("Candidates retain a faint coloured spill over 5% of the clear strip for at least 300ms: $summary",
            longestRun(active) { it.candidate.fraction6 >= 0.05 } >= 6)
        assertTrue("Candidate spill remains below a quarter of the visibly bright keyboard field: $summary",
            active.maxOf { it.candidate.peak } <= active.maxOf { it.gap.peak } * 0.25)
        assertTrue("Continuous neon light must preserve dark unpressed cap interiors",
            inactiveCaps.isNotEmpty() && inactiveCaps.maxOf { it.meanMaxRgb } < 45.0)
        // Bound contrast conservatively using the candidate strip's brightest
        // channel as a neutral background, which is brighter than its saturated colour.
        fun neutralLuminance(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val worstCandidateContrast = (neutralLuminance(242) + 0.05) /
            (neutralLuminance(active.maxOf { it.candidate.peak }) + 0.05)
        assertTrue("Candidate text must retain at least 4.5:1 contrast over its neon background",
            worstCandidateContrast >= 4.5)
        assertTrue("Stopping input must not release the old light into a much brighter wash: $summary",
            tail.maxOf { it.fixedGap.meanMaxRgb } <= active.maxOf { it.fixedGap.meanMaxRgb } * 1.6 + 5.0)
        val restFrames = visibility.filter { it.frame * 50L >= lastDown + timing.completeTail + 100L }
        assertTrue("The review must retain at least 300ms after the complete configured tail", restFrames.size >= 6)
        assertTrue("After the soft tail, actual keyboard gaps return to the black baseline",
            restFrames.all { it.gap.meanMaxRgb <= 3.0 && it.gap.colour30 == 0 })
        assertTrue("The candidate strip also returns to its black baseline after the press wave",
            restFrames.all { it.candidate.meanMaxRgb <= 3.0 && it.candidate.colour30 == 0 })
        previews.stop()
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun oneReleasedKeyDimsThroughTheActualViewInsteadOfDisappearingOnUp() {
        val timing = defaultSoftTiming()
        ThemeManager.prefs.idleBreathing.setValue(false)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
        activity.setContentView(keyboard)
        controller.visible()
        advance(32)
        layout(keyboard, 600, 360)
        assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
        keyboard.onInputMethodUpdate(english(true))
        // Case changes request a new text measurement. Settle that layout before
        // comparing the resting glyph with any pressed/released frame.
        layout(keyboard, 600, 360)
        keyboard.keyActionListener = KeyActionListener { _, _ -> }
        val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
        effect.prepareTexturesForTest()
        ReflectionHelpers.setField(effect, "random", Random(1401))
        var reviewTime = SystemClock.uptimeMillis()
        ReflectionHelpers.setField(effect, "clock", { reviewTime } as () -> Long)
        val start = reviewTime
        val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "G" }
        val rect = bounds(keyboard, key)
        val idle = render(keyboard)
        val idlePixels = IntArray(keyboard.width * keyboard.height).also {
            idle.getPixels(it, 0, keyboard.width, 0, 0, keyboard.width, keyboard.height)
        }
        val gapMask = BooleanArray(idlePixels.size) { index ->
            maxOf(Color.red(idlePixels[index]), Color.green(idlePixels[index]), Color.blue(idlePixels[index])) <= 3
        }
        // The still-lit G surface and its static edge are not evidence of a travelling wave.
        exclude(gapMask, rect, keyboard.width, keyboard.height)
        val sampleX = rect.left + 10
        val sampleY = rect.bottom - 15
        val rest = idle.getPixel(sampleX, sampleY)
        var earlyDistance = 0
        var finalDistance = 0
        val finalFrame = ((timing.completeTail + 450L + 49L) / 50L).toInt()
        val visibility = ArrayList<LightArea>()
        for (frame in 0..finalFrame) {
            reviewTime = start + frame * 50L
            if (frame < 2) {
                val action = if (frame == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                val event = MotionEvent.obtain(start, reviewTime, action, rect.exactCenterX(), rect.exactCenterY(), 0)
                keyboard.dispatchTouchEvent(event)
                event.recycle()
            }
            layout(keyboard, 600, 360)
            render(keyboard).recycle()
            val surfaceFrame = renderSurfaces(keyboard, listOf(key))
            val image = surfaceFrame.image
            save(image, "v15-soft-release/frame-%03d".format(frame))
            val pixel = surfaceFrame.colourAtRestPoint(key, keyboard, sampleX.toFloat(), sampleY.toFloat())
            val difference = abs(Color.red(pixel) - Color.red(rest)) +
                abs(Color.green(pixel) - Color.green(rest)) + abs(Color.blue(pixel) - Color.blue(rest))
            if (frame == 1) earlyDistance = difference
            if (frame == finalFrame) finalDistance = difference
            val pixels = IntArray(idlePixels.size)
            image.getPixels(pixels, 0, keyboard.width, 0, 0, keyboard.width, keyboard.height)
            visibility.add(lightArea(pixels, gapMask, keyboard.width))
            image.recycle()
            advance(50)
        }
        val csv = "frame,time_ms,gap_pixels,gap_mean_maxrgb,gap_peak,gap_colour_gt30,gap_colour_gt60\n" +
            visibility.mapIndexed { frame, area -> String.format(Locale.US, "%d,%d,%d,%.2f,%d,%d,%d",
                frame, frame * 50, area.pixels, area.meanMaxRgb, area.peak, area.colour30, area.colour60) }
                .joinToString("\n") + "\n"
        File("build/outputs/effect-checks/v15-soft-release/visibility.csv").writeText(csv)
        File("build/outputs/effect-checks/v15-soft-release/preview.txt").writeText(
            "source=Android TextKeyboard\ngraphics=Robolectric NATIVE\nframes=${finalFrame + 1}\nfps=20\n" +
                "last_frame_ms=${finalFrame * 50}\nwidth=600\nheight=360\ndensity=hdpi\nseed=1401\n" +
                "colour_mode=${ThemeManager.prefs.pressColorMode.getValue()}\n" +
                "random_palette=${ThemeManager.prefs.pressEffectPalette.getValue()}\n" +
                "input=G\ndown_ms=0\nup_ms=50\npopup=absent (isolated key surface and travelling light)\n" +
                "idle_breathing=false (isolated press-wave review)\n" + timing.provenance() + "\n" + csv)
        assertTrue("The key retains visible colour on UP before its floating return, using the actual drawn cap", earlyDistance > 50)
        assertTrue("The released key colour returns towards its original dark background",
            finalDistance < earlyDistance / 3)
        assertTrue("The softer default keeps external light visible beyond the old short wave",
            visibility.filterIndexed { frame, _ -> frame * 50L in 550L..750L }.any { it.colour30 >= 500 })
        assertTrue("The isolated single-key wave fully returns to the black gaps after its configured tail",
            visibility.filterIndexed { frame, _ -> frame * 50L >= timing.completeTail + 100L }
                .all { it.meanMaxRgb <= 3.0 && it.colour30 == 0 })
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun arbitrarySingleAndTwoColourChoicesRenderThroughTheProductionKeyboard() {
        val timing = defaultSoftTiming()
        val prefs = ThemeManager.prefs
        prefs.idleBreathing.setValue(false)
        val orange = Color.parseColor("#FF8A32")
        val custom = setOf(Color.parseColor("#18FFC1"), Color.parseColor("#D96EFF"))
        val provenance = StringBuilder("source=Android TextKeyboard/PopupEntryUi\ngraphics=Robolectric NATIVE\n" +
            "density=hdpi\nseed=1401\nidle_breathing=false (isolated palette review)\n" + timing.provenance())
        for (mode in listOf(ThemePrefs.PressColorMode.Single, ThemePrefs.PressColorMode.Custom)) {
            prefs.pressColorMode.setValue(mode)
            prefs.pressSingleColor.setValue(orange)
            prefs.pressUserColors.setValue("#18FFC1,#D96EFF")
            val allowed = if (mode == ThemePrefs.PressColorMode.Single) setOf(orange) else custom
            val name = if (mode == ThemePrefs.PressColorMode.Single) "single-ff8a32" else "custom-18ffc1-d96eff"
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setTheme(R.style.Theme_InputViewTheme)
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            root.addView(keyboard, FrameLayout.LayoutParams(600, 360))
            keyboard.keyActionListener = KeyActionListener { _, _ -> }
            val previews = PreviewHost(root)
            keyboard.popupActionListener = previews.listener
            controller.visible()
            advance(32)
            layout(root, 600, 360)
            assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
            keyboard.onInputMethodUpdate(english(true))
            layout(root, 600, 360)
            val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
            effect.prepareTexturesForTest()
            ReflectionHelpers.setField(effect, "random", Random(1401))
            var reviewTime = SystemClock.uptimeMillis()
            ReflectionHelpers.setField(effect, "clock", { reviewTime } as () -> Long)
            val start = reviewTime
            val chosen = mutableSetOf<Int>()
            for ((index, label) in listOf("G", "H", "J", "F").withIndex()) {
                val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == label }
                val rectangle = bounds(keyboard, key)
                val downTime = start + index * 250L
                assertEquals(downTime, reviewTime)
                val down = MotionEvent.obtain(downTime, reviewTime, MotionEvent.ACTION_DOWN,
                    rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
                assertTrue(keyboard.dispatchTouchEvent(down))
                down.recycle()
                val colour = PressEffect.colorForKey(key.id)!!
                assertTrue("The actual press must choose from the configured arbitrary RGB values", colour in allowed)
                chosen.add(colour)
                reviewTime += 50L
                advance(50)
                layout(root, 600, 360)
                val popup = previews.entry(key.id)!!
                assertTrue("The production popup lifecycle must be visible during the held press", popup.root.alpha > 0.75f)
                assertEquals(colour, (popup.textView.background as GradientDrawable).color?.defaultColor)
                render(root).recycle()
                val surfaceFrame = renderSurfaces(root, listOf(key))
                val image = surfaceFrame.image
                save(image, "v15-soft-palettes/$name-press-$index")
                // Keep the original colour sample inside the actual drawn cap.
                // The captured Canvas includes translation and geometry-limited scaling.
                val face = surfaceFrame.colourAtRestPoint(key, root,
                    rectangle.left + 10f, rectangle.bottom - 15f)
                assertTrue("The configured colour is visible on the real key face",
                    maxOf(Color.red(face), Color.green(face), Color.blue(face)) > 100)
                image.recycle()
                val up = MotionEvent.obtain(downTime, reviewTime, MotionEvent.ACTION_UP,
                    rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
                assertTrue(keyboard.dispatchTouchEvent(up))
                up.recycle()
                reviewTime += 200L
                advance(200)
            }
            assertEquals("The fixed review sequence must demonstrate every configured colour", allowed, chosen)
            layout(root, 600, 360)
            save(render(root), "v15-soft-palettes/$name-external-light")
            provenance.append("mode=$mode\ncolours=${chosen.joinToString { "#%06X".format(it and 0xffffff) }}\n" +
                "press_frames_ms=50,300,550,800\nexternal_light_ms=1000\n")
            previews.stop()
            keyboard.onDetach()
            controller.pause().stop().destroy()
        }
        File("build/outputs/effect-checks/v15-soft-palettes/preview.txt").writeText(provenance.toString())
    }

    @Test
    fun shortTypingBurstRendersTheMergedFieldAndCompleteReleaseTail() {
        val timing = defaultSoftTiming()
        val prefs = ThemeManager.prefs
        prefs.idleBreathing.setValue(false)
        assertEquals(ThemePrefs.PressColorMode.Random, prefs.pressColorMode.getValue())
        assertEquals(ThemePrefs.PressEffectPalette.Cyberpunk, prefs.pressEffectPalette.getValue())
        assertTrue(prefs.portraitNumberRow.getValue())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val theme = ThemePreset.XuancaiBlackV09
        val adapter = HorizontalCandidateViewAdapter(theme)
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
        }
        val candidates = CandidateUi(activity, theme, list)
        val bar = RippleBarView(activity).apply { setBackgroundColor(Color.BLACK) }
        bar.addView(candidates.root, FrameLayout.LayoutParams(600, 60))
        root.addView(bar, FrameLayout.LayoutParams(600, 60))
        val keyboard = TextKeyboard(activity, theme)
        root.addView(keyboard, FrameLayout.LayoutParams(600, 360).apply { topMargin = 60 })
        bar.keyboard = keyboard
        val previews = PreviewHost(root)
        keyboard.popupActionListener = previews.listener
        controller.visible()
        advance(32)
        layout(root)
        assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
        keyboard.onInputMethodUpdate(english(false))
        val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
        effect.prepareTexturesForTest()
        ReflectionHelpers.setField(effect, "random", Random(1401))
        var reviewTime = SystemClock.uptimeMillis()
        ReflectionHelpers.setField(effect, "clock", { reviewTime } as () -> Long)
        val start = reviewTime
        val input = "liangge" // Seven presses across the letter rows, completing in 950ms.
        val recorded = javaClass.getResourceAsStream("/xuancai-typing-candidates.tsv")!!
            .bufferedReader().use { it.readLines().take(input.length).map { row -> row.split('\t') } }
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
        val idle = render(root)
        save(idle, "v15-reference-burst/rest")
        val idlePixels = IntArray(root.width * root.height).also {
            idle.getPixels(it, 0, root.width, 0, 0, root.width, root.height)
        }
        idle.recycle()
        val keyboardRectangle = screenBoundsIn(keyboard, root)!!
        val barRectangle = screenBoundsIn(bar, root)!!
        val gaps = BooleanArray(idlePixels.size) { index ->
            keyboardRectangle.contains(index % root.width, index / root.width) &&
                maxOf(Color.red(idlePixels[index]), Color.green(idlePixels[index]), Color.blue(idlePixels[index])) <= 3
        }
        val candidateStrip = BooleanArray(idlePixels.size) { index ->
            val y = index / root.width
            barRectangle.contains(index % root.width, y) && y < barRectangle.top + barRectangle.height() / 8
        }
        val renderedKeys = keys(keyboard).associateBy { it.id }
        val keyRectangles = renderedKeys.mapValues { screenBoundsIn(it.value, root)!! }
        val fixedGapMask = gaps.copyOf()
        keys(keyboard).filter { (it.def as? KeyDef.Appearance.Text)?.displayText?.lowercase() in input.map(Char::toString) }
            .forEach { key ->
                val rectangle = keyRectangles.getValue(key.id)
                exclude(fixedGapMask, rectangle, root.width, root.height)
                val preview = Rect(
                    ((rectangle.left + rectangle.right - root.dp(44)) / 2).coerceIn(0, root.width - root.dp(44)),
                    rectangle.bottom - root.dp(100) - root.dp(prefs.keyVerticalMargin.getValue()), 0, 0)
                preview.right = preview.left + root.dp(44)
                preview.bottom = preview.top + root.dp(52)
                exclude(fixedGapMask, preview, root.width, root.height)
            }
        val lastPressTimes = mutableMapOf<Int, Long>()
        val lastDown = (input.length - 1) * 150L
        val lastUp = lastDown + 50L
        val finalFrame = ((lastDown + timing.completeTail + 550L + 49L) / 50L).toInt()
        val samples = ArrayList<VisibilityFrame>()
        val keyframes = setOf(0L, 150L, 450L, 750L, 950L, 1200L, 1500L, 2400L, 3650L, 4200L)
        for (frame in 0..finalFrame) {
            val elapsed = frame * 50L
            reviewTime = start + elapsed
            val index = frame / 3
            if (index < input.length && frame % 3 < 2) {
                val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == input[index].uppercase() }
                val rectangle = bounds(keyboard, key)
                val action = if (frame % 3 == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                if (action == MotionEvent.ACTION_DOWN) lastPressTimes[key.id] = reviewTime
                val event = MotionEvent.obtain(start + index * 150L, reviewTime, action,
                    rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
                assertTrue(keyboard.dispatchTouchEvent(event))
                event.recycle()
                if (action == MotionEvent.ACTION_DOWN) {
                    val popup = previews.entry(key.id)!!
                    assertEquals(PressEffect.colorForKey(key.id),
                        (popup.textView.background as GradientDrawable).color?.defaultColor)
                }
            }
            layout(root)
            render(root).recycle()
            val image = render(root)
            save(image, "v15-reference-burst/frame-%03d".format(frame))
            if (elapsed in keyframes) save(image, "v15-reference-burst/keyframes/%04d-ms".format(elapsed))
            val gapMask = gaps.copyOf()
            val candidateMask = candidateStrip.copyOf()
            lastPressTimes.forEach { (id, pressedAt) ->
                if (reviewTime - pressedAt <= timing.releasedKeyTotal || faceOrMotionActive(renderedKeys.getValue(id)))
                    exclude(gapMask, keyRectangles.getValue(id), root.width, root.height)
            }
            previews.visibleCharacterBounds().forEach {
                exclude(gapMask, it, root.width, root.height)
                exclude(candidateMask, it, root.width, root.height)
            }
            val pixels = IntArray(idlePixels.size)
            image.getPixels(pixels, 0, root.width, 0, 0, root.width, root.height)
            samples.add(VisibilityFrame(frame, lightArea(pixels, gapMask, root.width),
                lightArea(pixels, candidateMask, root.width), lightArea(pixels, fixedGapMask, root.width)))
            image.recycle()
            advance(50)
        }
        val active = samples.filter { it.frame * 50L <= lastUp }
        val tail = samples.filter { it.frame * 50L > lastUp }
        val summary = String.format(Locale.US,
            "active_fixed_gap_peak_mean=%.2f\ntail_fixed_gap_peak_mean=%.2f\n" +
                "external_colour60_peak_area=%d\ncandidate_colour30_peak_area=%d\n",
            active.maxOf { it.fixedGap.meanMaxRgb }, tail.maxOf { it.fixedGap.meanMaxRgb },
            samples.maxOf { it.gap.colour60 }, samples.maxOf { it.candidate.colour30 })
        val csv = "frame,time_ms,gap_pixels,gap_mean_maxrgb,gap_peak,gap_colour_gt30,gap_colour_gt60," +
            "gap_centre_x,gap_centre_y,candidate_strip_pixels,candidate_mean_maxrgb,candidate_peak," +
            "candidate_colour_gt30,candidate_colour_gt60,fixed_gap_mean_maxrgb\n" +
            samples.joinToString("\n") { it.csv() } + "\n"
        File("build/outputs/effect-checks/v15-reference-burst/visibility.csv").writeText(csv)
        File("build/outputs/effect-checks/v15-reference-burst/preview.txt").writeText(
            "source=Android TextKeyboard/CandidateUi/RippleBarView/PopupEntryUi\ngraphics=Robolectric NATIVE\n" +
                "frames=${finalFrame + 1}\nfps=20\nlast_frame_ms=${finalFrame * 50}\n" +
                "width=600\nheight=420\ndensity=hdpi\nlogical_width_dp=400\nseed=1401\n" +
                "colour_mode=${prefs.pressColorMode.getValue()}\nrandom_palette=${prefs.pressEffectPalette.getValue()}\n" +
                "portrait_number_row=${prefs.portraitNumberRow.getValue()}\ninput=$input\n" +
                "press_count=${input.length}\nkey_interval_ms=150\ntouch_hold_ms=50\nlast_down_ms=$lastDown\nlast_up_ms=$lastUp\n" +
                "idle_breathing=false (isolated merged press-field review)\n" + timing.provenance() +
                "candidates=replay of real bundled Rime fixture; not live native execution in this UI test\n" +
                "measurement=baseline black gaps; active key cells and visible popup glyph bounds excluded\n" +
                "motion_metrics=descriptive only; no invented Samsung brightness or timing threshold\n" + summary + "\n" + csv)
        assertEquals(input, typed.toString())
        assertEquals(recorded.last()[1], adapter.candidates.first().text)
        val completeRest = samples.filter { it.frame * 50L >= lastDown + timing.completeTail + 100L }
        assertTrue("The short burst must retain at least 300ms after its complete configured tail", completeRest.size >= 6)
        assertTrue("The merged field returns to the actual black-gap baseline",
            completeRest.all { it.gap.meanMaxRgb <= 3.0 && it.gap.colour30 == 0 })
        assertTrue("The actual candidate strip returns to its black baseline after the burst",
            completeRest.all { it.candidate.meanMaxRgb <= 3.0 && it.candidate.colour30 == 0 })
        previews.stop()
        keyboard.onDetach()
        controller.pause().stop().destroy()
    }

    @Test
    fun fPressShowsTheRealSoftFieldWithTheNumberRowBothEnabledAndDisabled() {
        val timing = defaultSoftTiming()
        val prefs = ThemeManager.prefs
        prefs.idleBreathing.setValue(false)
        // Hold hue constant so only the real number-row geometry changes between the two clips.
        prefs.pressColorMode.setValue(ThemePrefs.PressColorMode.Single)
        prefs.pressSingleColor.setValue(Color.parseColor("#00F0FF"))
        val keyframeTimes = setOf(0L, 50L, 150L, 250L, 400L, 700L, 1500L, 1850L, 2400L, 3200L)
        // Keep the existing 3200ms comparison keyframe after reducing ignition.
        val finalFrame = maxOf(64, ((timing.completeTail + 450L + 49L) / 50L).toInt())
        for (numberRow in listOf(true, false)) {
            prefs.portraitNumberRow.setValue(numberRow)
            val keyboardHeight = if (numberRow) 360 else 288
            val folder = "v15-f-review-${if (numberRow) "on" else "off"}"
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setTheme(R.style.Theme_InputViewTheme)
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            root.addView(keyboard, FrameLayout.LayoutParams(600, keyboardHeight))
            val previews = PreviewHost(root)
            keyboard.popupActionListener = previews.listener
            val typed = StringBuilder()
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) typed.append(action.act)
            }
            controller.visible()
            advance(32)
            layout(root, 600, keyboardHeight)
            assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
            keyboard.onInputMethodUpdate(english(true))
            layout(root, 600, keyboardHeight)
            assertEquals(if (numberRow) 5 else 4, keyboard.childCount)
            assertEquals(if (numberRow) 10 else 0, keys(keyboard).count {
                val label = (it.def as? KeyDef.Appearance.Text)?.displayText
                label?.length == 1 && label[0].isDigit()
            })
            val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
            effect.prepareTexturesForTest()
            ReflectionHelpers.setField(effect, "random", Random(1401))
            var reviewTime = SystemClock.uptimeMillis()
            ReflectionHelpers.setField(effect, "clock", { reviewTime } as () -> Long)
            val start = reviewTime
            val key = keys(keyboard).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "F" }
            val rectangle = bounds(keyboard, key)
            val rest = render(root)
            save(rest, "$folder/rest")
            val idlePixels = IntArray(root.width * root.height).also {
                rest.getPixels(it, 0, root.width, 0, 0, root.width, root.height)
            }
            rest.recycle()
            val gaps = BooleanArray(idlePixels.size) { index ->
                maxOf(Color.red(idlePixels[index]), Color.green(idlePixels[index]), Color.blue(idlePixels[index])) <= 3
            }
            exclude(gaps, rectangle, root.width, root.height)
            // With the extra black cap stroke removed, bright real gaps occupy
            // a larger share of the mask. Check the dark actual caps separately.
            val inactiveCaps = BooleanArray(idlePixels.size)
            keys(keyboard).filter { other ->
                val label = (other.def as? KeyDef.Appearance.Text)?.displayText.orEmpty()
                other.id != key.id && label.length == 1 && label[0].lowercaseChar() in 'a'..'z'
            }.forEach { other ->
                val cap = bounds(keyboard, other).apply { inset(other.hMargin + 3, other.vMargin + 3) }
                for (y in cap.top until cap.bottom) for (x in cap.left until cap.right) {
                    val index = y * root.width + x
                    val c = idlePixels[index]
                    val high = maxOf(Color.red(c), Color.green(c), Color.blue(c))
                    val low = minOf(Color.red(c), Color.green(c), Color.blue(c))
                    if (high in 8..35 && high - low <= 12) inactiveCaps[index] = true
                }
            }
            val visibility = ArrayList<LightArea>()
            val popupAlpha = ArrayList<Float>()
            for (frame in 0..finalFrame) {
                val elapsed = frame * 50L
                reviewTime = start + elapsed
                if (frame < 2) {
                    val event = MotionEvent.obtain(start, reviewTime,
                        if (frame == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                        rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
                    assertTrue(keyboard.dispatchTouchEvent(event))
                    event.recycle()
                    val popup = previews.entry(key.id)!!
                    assertEquals("f", popup.textView.text.toString())
                    assertEquals(prefs.pressSingleColor.getValue(), PressEffect.colorForKey(key.id))
                    assertEquals(PressEffect.colorForKey(key.id),
                        (popup.textView.background as GradientDrawable).color?.defaultColor)
                }
                layout(root, 600, keyboardHeight)
                render(root).recycle()
                val image = render(root)
                save(image, "$folder/frame-%03d".format(frame))
                if (elapsed in keyframeTimes) save(image, "$folder/keyframes/%04d-ms".format(elapsed))
                val mask = gaps.copyOf()
                previews.visibleCharacterBounds().forEach { exclude(mask, it, root.width, root.height) }
                val pixels = IntArray(idlePixels.size)
                image.getPixels(pixels, 0, root.width, 0, 0, root.width, root.height)
                val capMask = inactiveCaps.copyOf()
                previews.visibleCharacterBounds().forEach { exclude(capMask, it, root.width, root.height) }
                val darkCaps = lightArea(pixels, capMask, root.width)
                assertTrue("The F comparison keeps real inactive key caps dark without adding black outlines",
                    darkCaps.pixels >= 1000 && darkCaps.peak < 45)
                visibility.add(lightArea(pixels, mask, root.width))
                popupAlpha.add(previews.entry(key.id)?.root?.alpha ?: 0f)
                image.recycle()
                advance(50)
            }
            val csv = "frame,time_ms,gap_pixels,gap_mean_maxrgb,gap_peak,gap_colour_gt30,gap_colour_gt60,popup_alpha\n" +
                visibility.mapIndexed { frame, area -> String.format(Locale.US, "%d,%d,%d,%.2f,%d,%d,%d,%.3f",
                    frame, frame * 50, area.pixels, area.meanMaxRgb, area.peak, area.colour30, area.colour60,
                    popupAlpha[frame]) }.joinToString("\n") + "\n"
            File("build/outputs/effect-checks/$folder/visibility.csv").writeText(csv)
            File("build/outputs/effect-checks/$folder/preview.txt").writeText(
                "source=Android TextKeyboard/PopupEntryUi\ngraphics=Robolectric NATIVE\n" +
                    "frames=${finalFrame + 1}\nfps=20\nlast_frame_ms=${finalFrame * 50}\n" +
                    "width=600\nheight=$keyboardHeight\ndensity=hdpi\nlogical_width_dp=400\nseed=1401\n" +
                    "colour_mode=${prefs.pressColorMode.getValue()}\ncolour=#00F0FF (same hue for both layout comparisons)\n" +
                    "portrait_number_row=$numberRow\nkeyboard_rows=${keyboard.childCount}\n" +
                    "input=F\ndown_ms=0\nup_ms=50\ntyped=f\n" +
                    "key_bounds_px=${rectangle.flattenToString()}\n" +
                    "popup=production PopupEntryUi and PopupPreviewLifecycle; per-frame alpha in visibility.csv\n" +
                    "idle_breathing=false (isolated press-wave review)\n" + timing.provenance() +
                    "keyframes_ms=${keyframeTimes.joinToString(",")}\n" +
                    "measurement=baseline black gaps; F touch cell and visible popup glyph bounds excluded\n" +
                    "coloured_pixel=maxRGB>30/60 and channel spread>=15\n\n" + csv)
            assertEquals("The real down/up route must commit exactly one English F", "f", typed.toString())
            assertTrue("The real F press must produce a visible field outside its key and popup",
                visibility.filterIndexed { frame, _ -> frame * 50L in 150L..300L }.any { it.colour30 >= 1000 })
            assertTrue("The soft F field must remain visible beyond the old short wave",
                visibility.filterIndexed { frame, _ -> frame * 50L in 550L..750L }.any { it.colour30 >= 500 })
            assertTrue("The F popup remains readable at and just after key release",
                popupAlpha[1] >= 0.75f && popupAlpha[2] > 0f)
            assertTrue("The real popup release lifecycle completes rather than sticking to the key",
                popupAlpha.drop(5).all { it == 0f })
            assertTrue("Both F layouts return to black after the complete configured tail",
                visibility.filterIndexed { frame, _ -> frame * 50L >= timing.completeTail + 100L }
                    .all { it.meanMaxRgb <= 3.0 && it.colour30 == 0 })
            previews.stop()
            keyboard.onDetach()
            controller.pause().stop().destroy()
        }
    }

    private data class LegendGeometry(val keyBounds: Rect, val textBounds: Rect, val values: List<Float>)

    private fun legendGeometry(key: TextKeyView, root: View): LegendGeometry {
        val text = key.mainText
        val paint = text.paint
        val metrics = paint.fontMetrics
        return LegendGeometry(screenBoundsIn(key, root)!!, screenBoundsIn(text, root)!!, listOf(
            key.translationX, key.translationY, key.rotation, key.scaleX, key.scaleY,
            text.translationX, text.translationY, text.rotation, text.scaleX, text.scaleY,
            ReflectionHelpers.getField<Float>(text, "baselineX"),
            ReflectionHelpers.getField<Float>(text, "baselineY"),
            ReflectionHelpers.getField<Float>(text, "textScaleX"),
            ReflectionHelpers.getField<Float>(text, "textScaleY"),
            paint.textSize, paint.textScaleX, paint.textSkewX, paint.measureText(text.text.toString()),
            metrics.top, metrics.bottom, text.contrastOutlineWidth))
    }

    private fun assertStableLegend(label: String, expected: LegendGeometry, actual: LegendGeometry) {
        assertEquals("$label touch-cell layout coordinates stay fixed", expected.keyBounds, actual.keyBounds)
        assertEquals("$label text-view coordinates stay fixed", expected.textBounds, actual.textBounds)
        expected.values.zip(actual.values).forEachIndexed { index, (before, after) ->
            assertEquals("$label baseline, scale and paint measurement stay fixed (field $index)", before, after, 0.0001f)
        }
    }

    private data class LegendContrast(val strongPixels: Int, val peak: Int, val mean: Double)

    /** Compare the actual frame with the same Android view drawing only its glyph hidden.
     * The hidden reference is used solely for measurement; exported PNGs contain the real glyph.
     * This includes both real fill and the fixed production contrast outline, independent of ink colour.
     */
    private fun legendContrast(image: Bitmap, key: TextKeyView, root: View): LegendContrast {
        // The Canvas moves the ink within the fixed touch cell. Its resting
        // TextView rectangle does not enclose every pressed/rebounding glyph.
        val rectangle = screenBoundsIn(key, root)!!
        val visibility = key.mainText.visibility
        val background = try {
            key.mainText.visibility = View.INVISIBLE
            render(root)
        } finally {
            key.mainText.visibility = visibility
        }
        var count = 0
        var strong = 0
        var sum = 0L
        var peak = 0
        for (y in rectangle.top.coerceAtLeast(0) until rectangle.bottom.coerceAtMost(root.height)) {
            for (x in rectangle.left.coerceAtLeast(0) until rectangle.right.coerceAtMost(root.width)) {
                val foreground = image.getPixel(x, y)
                val behind = background.getPixel(x, y)
                val contrast = maxOf(abs(Color.red(foreground) - Color.red(behind)),
                    abs(Color.green(foreground) - Color.green(behind)), abs(Color.blue(foreground) - Color.blue(behind)))
                if (contrast > 0) { count++; sum += contrast; peak = maxOf(peak, contrast) }
                if (contrast >= 40) strong++
            }
        }
        background.recycle()
        return LegendContrast(strong, peak, sum.toDouble() / count.coerceAtLeast(1))
    }

    @Test
    fun stableThirtyFpsPressesKeepActualGlyphCoordinatesAndPopupSizeThroughCompleteTails() {
        stableNativeSequences(30, listOf(Triple("v15-stable-f-on", "f", true),
            Triple("v15-stable-f-off", "f", false), Triple("v15-stable-burst", "liangge", true)))
    }

    @Test
    fun continuousSixtyFpsPressesUseIrregularRealTouchesAndKeepStableReadableGlyphs() {
        stableNativeSequences(60, listOf(Triple("v15-continuous-f-on", "f", true),
            Triple("v15-continuous-f-off", "f", false), Triple("v15-continuous-burst", "liangge", true)),
            listOf(0L, 105L, 230L, 320L, 440L, 545L, 650L))
    }

    private fun stableNativeSequences(fps: Int, cases: List<Triple<String, String, Boolean>>,
                                      burstPressTimes: List<Long>? = null) {
        val timing = defaultSoftTiming()
        val prefs = ThemeManager.prefs
        prefs.idleBreathing.setValue(false)
        assertEquals(ThemePrefs.KeyMotionEffect.Press, prefs.keyMotionEffect.getValue())
        for ((folder, input, numberRow) in cases) {
            // A shorter configured tail must not leave old longer recordings in
            // this fixture's own generated frame folder.
            File("build/outputs/effect-checks/$folder").listFiles()?.filter {
                it.name.startsWith("frame-") && it.extension == "png"
            }?.forEach { it.delete() }
            val burst = input.length > 1
            prefs.portraitNumberRow.setValue(numberRow)
            prefs.pressColorMode.setValue(if (burst) ThemePrefs.PressColorMode.Random else ThemePrefs.PressColorMode.Single)
            prefs.pressEffectPalette.setValue(ThemePrefs.PressEffectPalette.Cyberpunk)
            prefs.pressSingleColor.setValue(Color.parseColor("#00F0FF"))
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setTheme(R.style.Theme_InputViewTheme)
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val theme = ThemePreset.XuancaiBlackV09
            val keyboardHeight = if (numberRow) 360 else 288
            val height = keyboardHeight + if (burst) 60 else 0
            val barHeight = if (burst) 60 else 0
            val adapter = HorizontalCandidateViewAdapter(theme)
            val bar = if (burst) RippleBarView(activity).apply {
                setBackgroundColor(Color.BLACK)
                val list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    this.adapter = adapter
                    itemAnimator = null
                }
                addView(CandidateUi(activity, theme, list).root, FrameLayout.LayoutParams(600, 60))
                root.addView(this, FrameLayout.LayoutParams(600, 60))
            } else null
            val keyboard = TextKeyboard(activity, theme)
            root.addView(keyboard, FrameLayout.LayoutParams(600, keyboardHeight).apply { topMargin = barHeight })
            bar?.keyboard = keyboard
            val previews = PreviewHost(root)
            keyboard.popupActionListener = previews.listener
            val recorded = if (burst) javaClass.getResourceAsStream("/xuancai-typing-candidates.tsv")!!
                .bufferedReader().use { it.readLines().take(input.length).map { row -> row.split('\t') } } else emptyList()
            val typed = StringBuilder()
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) {
                    typed.append(action.act)
                    if (burst) {
                        val row = recorded[typed.length - 1]
                        assertEquals(row[0], typed.toString())
                        val words = row.drop(1).map { CandidateWord("", it, "") }.toTypedArray()
                        adapter.updateCandidates(words, words.size)
                    }
                }
            }
            controller.visible()
            advance(32)
            layout(root, 600, height)
            assertTrue(keyboard.isAttachedToWindow && keyboard.isShown)
            keyboard.onInputMethodUpdate(english(!burst))
            // Updating uppercase/lowercase invalidates text measurement. The baseline
            // and the whole recording both use the settled production layout.
            layout(root, 600, height)
            val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
            effect.prepareTexturesForTest()
            ReflectionHelpers.setField(effect, "random", Random(1401))
            var reviewTime = SystemClock.uptimeMillis()
            val start = reviewTime
            ReflectionHelpers.setField(effect, "clock", { reviewTime } as () -> Long)
            val touched = keys(keyboard).filterIsInstance<TextKeyView>().filter {
                (it.def as? KeyDef.Appearance.Text)?.displayText?.lowercase() in input.map(Char::toString)
            }.associateBy { (it.def as KeyDef.Appearance.Text).displayText.lowercase() }
            val idle = render(root)
            save(idle, "$folder/rest")
            val idlePixels = IntArray(root.width * root.height).also {
                idle.getPixels(it, 0, root.width, 0, 0, root.width, root.height)
            }
            idle.recycle()
            val geometry = touched.mapValues { legendGeometry(it.value, root) }
            val keyboardBounds = screenBoundsIn(keyboard, root)!!
            val keyBounds = touched.mapValues { screenBoundsIn(it.value, root)!! }
            val gaps = BooleanArray(idlePixels.size) { index ->
                keyboardBounds.contains(index % root.width, index / root.width) &&
                    maxOf(Color.red(idlePixels[index]), Color.green(idlePixels[index]), Color.blue(idlePixels[index])) <= 3
            }
            val candidateStrip = BooleanArray(idlePixels.size) { index -> barHeight > 0 && index / root.width < barHeight / 8 }
            val fixedGaps = gaps.copyOf()
            keyBounds.values.forEach { rectangle ->
                exclude(fixedGaps, rectangle, root.width, root.height)
                val popupLeft = ((rectangle.left + rectangle.right - root.dp(44)) / 2).coerceIn(0, root.width - root.dp(44))
                val popupTop = rectangle.bottom - root.dp(100) - root.dp(prefs.keyVerticalMargin.getValue())
                exclude(fixedGaps, Rect(popupLeft, popupTop, popupLeft + root.dp(44), popupTop + root.dp(52)), root.width, root.height)
            }
            data class Gesture(val time: Long, val index: Int, val action: Int)
            val pressTimes = if (burst && burstPressTimes != null) burstPressTimes else input.indices.map { it * 150L }
            assertEquals(input.length, pressTimes.size)
            assertEquals(0L, pressTimes.first())
            assertTrue("Every real key is released before the next DOWN", pressTimes.zipWithNext().all { it.second - it.first > 50L })
            val gestures = input.indices.flatMap { index -> listOf(Gesture(pressTimes[index], index, MotionEvent.ACTION_DOWN),
                Gesture(pressTimes[index] + 50L, index, MotionEvent.ACTION_UP)) }
            val lastDown = pressTimes.last()
            val endTime = lastDown + timing.completeTail + if (burst) 550L else 450L
            val finalFrame = ((endTime * fps + 999L) / 1000L).toInt()
            var elapsedNow = 0L
            fun advanceTo(target: Long) {
                assertTrue(target >= elapsedNow)
                reviewTime = start + target
                advance(target - elapsedNow)
                elapsedNow = target
            }
            var nextGesture = 0
            var focus = input.first().toString()
            val lastPressTimes = mutableMapOf<String, Long>()
            val samples = ArrayList<Pair<Long, VisibilityFrame>>()
            val popupAlpha = ArrayList<Float>()
            val inkRows = ArrayList<String>()
            val geometryRows = ArrayList<String>()
            val canvasRows = ArrayList<String>()
            val inkColours = ArrayList<Pair<Long, Int>>()
            val motionRows = ArrayList<String>()
            var previousPixels: IntArray? = null
            var minimumReadablePixels = Int.MAX_VALUE
            for (frame in 0..finalFrame) {
                // Direct native captures at their true timestamps. Touch events are
                // dispatched at their exact times between sampled frames, without interpolation.
                val elapsed = frame * 1000L / fps
                while (nextGesture < gestures.size && gestures[nextGesture].time <= elapsed) {
                    val gesture = gestures[nextGesture++]
                    advanceTo(gesture.time)
                    focus = input[gesture.index].toString()
                    val key = touched.getValue(focus)
                    val rectangle = bounds(keyboard, key)
                    val event = MotionEvent.obtain(start + pressTimes[gesture.index], reviewTime, gesture.action,
                        rectangle.exactCenterX(), rectangle.exactCenterY(), 0)
                    assertTrue(keyboard.dispatchTouchEvent(event))
                    event.recycle()
                    if (gesture.action == MotionEvent.ACTION_DOWN) {
                        lastPressTimes[focus] = gesture.time
                        val popup = previews.entry(key.id)!!
                        assertEquals(focus, popup.textView.text.toString())
                        assertEquals(PressEffect.colorForKey(key.id),
                            (popup.textView.background as GradientDrawable).color?.defaultColor)
                    }
                    previews.assertUnscaled()
                }
                advanceTo(elapsed)
                layout(root, 600, height)
                render(root).recycle()
                val surfaceFrame = renderSurfaces(root, touched.values)
                val image = surfaceFrame.image
                save(image, "$folder/frame-%03d".format(frame))
                previews.assertUnscaled()
                touched.forEach { (label, key) ->
                    val actual = legendGeometry(key, root)
                    assertStableLegend("$folder/$label at ${elapsed}ms", geometry.getValue(label), actual)
                    geometryRows.add("$frame,$elapsed,$label,${actual.keyBounds.left},${actual.keyBounds.top},${actual.keyBounds.right},${actual.keyBounds.bottom}," +
                        "${actual.textBounds.left},${actual.textBounds.top},${actual.textBounds.right},${actual.textBounds.bottom}," +
                        actual.values.joinToString(",") { String.format(Locale.US, "%.4f", it) })
                    val transform = surfaceFrame.transforms.getValue(key.id)
                    val matrixValues = FloatArray(9).also(transform::getValues)
                    val appearance = ReflectionHelpers.getField<View>(key, "appearanceView")
                    val centre = floatArrayOf(appearance.width / 2f, appearance.height / 2f)
                    transform.mapPoints(centre)
                    val depth = ReflectionHelpers.getField<KeyPressDepth>(key, "pressDepth")
                    val motionValues = listOf(depth.currentLift(), depth.currentVelocity(), key.floatingFaceOpacity(),
                        centre[0], centre[1]) + matrixValues.toList()
                    canvasRows.add("$frame,$elapsed,$label," +
                        motionValues.joinToString(",") { String.format(Locale.US, "%.6f", it) })
                }
                val focusKey = touched.getValue(focus)
                val contrast = legendContrast(image, focusKey, root)
                minimumReadablePixels = minOf(minimumReadablePixels, contrast.strongPixels)
                assertTrue("$folder/$focus at ${elapsed}ms must show a readable glyph including its real contrast outline, " +
                    "strong=${contrast.strongPixels}, peak=${contrast.peak}", contrast.strongPixels >= 12)
                val ink = focusKey.mainText.currentTextColor
                inkColours.add(elapsed to ink)
                val alpha = touched.values.maxOf { previews.entry(it.id)?.root?.alpha ?: 0f }
                popupAlpha.add(alpha)
                inkRows.add(String.format(Locale.US, "%d,%d,%s,%d,%d,%d,%d,%d,%.2f,%.3f",
                    frame, elapsed, focus, Color.red(ink), Color.green(ink), Color.blue(ink), contrast.strongPixels,
                    contrast.peak, contrast.mean, alpha))
                val mask = gaps.copyOf()
                val candidateMask = candidateStrip.copyOf()
                lastPressTimes.forEach { (label, pressedAt) ->
                    if (elapsed - pressedAt <= timing.releasedKeyTotal || faceOrMotionActive(touched.getValue(label)))
                        exclude(mask, keyBounds.getValue(label), root.width, root.height)
                }
                previews.visibleCharacterBounds().forEach {
                    exclude(mask, it, root.width, root.height)
                    exclude(candidateMask, it, root.width, root.height)
                }
                val pixels = IntArray(idlePixels.size)
                image.getPixels(pixels, 0, root.width, 0, 0, root.width, root.height)
                // A fixed exterior ROI measures field continuity independently of new
                // key-face flashes. Metrics are descriptive; the original visibility
                // and darkness gates remain unchanged in their dedicated fixtures.
                previousPixels?.let { previous ->
                    var channelDifference = 0L
                    var changedPixels = 0
                    var eligiblePixels = 0
                    for (index in pixels.indices) if (fixedGaps[index]) {
                        val red = abs(Color.red(pixels[index]) - Color.red(previous[index]))
                        val green = abs(Color.green(pixels[index]) - Color.green(previous[index]))
                        val blue = abs(Color.blue(pixels[index]) - Color.blue(previous[index]))
                        channelDifference += red + green + blue
                        if (maxOf(red, green, blue) > 0) changedPixels++
                        eligiblePixels++
                    }
                    motionRows.add(String.format(Locale.US, "%d,%d,%d,%d,%.5f", frame, elapsed,
                        eligiblePixels, changedPixels, channelDifference.toDouble() / (eligiblePixels.coerceAtLeast(1) * 3)))
                }
                previousPixels = pixels
                samples.add(elapsed to VisibilityFrame(frame, lightArea(pixels, mask, root.width),
                    lightArea(pixels, candidateMask, root.width), lightArea(pixels, fixedGaps, root.width)))
                image.recycle()
            }
            val directory = File("build/outputs/effect-checks/$folder")
            val visibilityCsv = "frame,time_ms,gap_pixels,gap_mean_maxrgb,gap_peak,gap_colour_gt30,gap_colour_gt60," +
                "gap_centre_x,gap_centre_y,candidate_strip_pixels,candidate_mean_maxrgb,candidate_peak," +
                "candidate_colour_gt30,candidate_colour_gt60,fixed_gap_mean_maxrgb\n" +
                samples.joinToString("\n") { (time, sample) ->
                    val oldColumns = sample.csv().split(',')
                    (listOf(oldColumns.first(), time.toString()) + oldColumns.drop(2)).joinToString(",")
                } + "\n"
            File(directory, "visibility.csv").writeText(visibilityCsv)
            File(directory, "ink.csv").writeText("frame,time_ms,key,ink_r,ink_g,ink_b,glyph_contrast_ge40_pixels,glyph_contrast_peak,glyph_contrast_mean,popup_alpha\n" +
                inkRows.joinToString("\n") + "\n")
            File(directory, "geometry.csv").writeText("frame,time_ms,key,key_left,key_top,key_right,key_bottom,text_left,text_top,text_right,text_bottom," +
                "key_tx,key_ty,key_rotation,key_sx,key_sy,text_tx,text_ty,text_rotation,text_sx,text_sy,baseline_x,baseline_y," +
                "text_scale_x,text_scale_y,paint_text_size,paint_scale_x,paint_skew_x,paint_text_width,font_top,font_bottom,outline_width\n" +
                geometryRows.joinToString("\n") + "\n")
            File(directory, "canvas-motion.csv").writeText("frame,time_ms,key,signed_position,velocity,face_opacity,drawn_centre_x,drawn_centre_y," +
                "canvas_scale_x,canvas_skew_x,canvas_translate_x,canvas_skew_y,canvas_scale_y,canvas_translate_y," +
                "canvas_perspective_0,canvas_perspective_1,canvas_perspective_2\n" +
                canvasRows.joinToString("\n") + "\n")
            File(directory, "motion.csv").writeText("frame,time_ms,fixed_exterior_pixels,changed_pixels,mean_abs_channel_change\n" +
                motionRows.joinToString("\n") + "\n")
            val inkStep = inkColours.zipWithNext().filter { it.first.first >= 100L && !burst }.maxOfOrNull { (before, after) ->
                maxOf(abs(Color.red(before.second) - Color.red(after.second)), abs(Color.green(before.second) - Color.green(after.second)),
                    abs(Color.blue(before.second) - Color.blue(after.second)))
            } ?: 0
            File(directory, "preview.txt").writeText("source=Android TextKeyboard/PopupEntryUi${if (burst) "/CandidateUi/RippleBarView" else ""}\n" +
                "graphics=Robolectric NATIVE\nframes=${finalFrame + 1}\nfps=$fps\nframe_time_ms=floor(frame*1000/$fps)\n" +
                "last_frame_ms=${finalFrame * 1000L / fps}\nwidth=600\nheight=$height\ndensity=hdpi\nseed=1401\n" +
                "colour_mode=${prefs.pressColorMode.getValue()}\nrandom_palette=${prefs.pressEffectPalette.getValue()}\n" +
                "single_colour=#00F0FF\nportrait_number_row=$numberRow\ninput=$input\n" +
                "key_interval_ms=${if (burstPressTimes == null || !burst) "150" else "irregular"}\n" +
                "press_times_ms=${pressTimes.joinToString(",")}\ntouch_hold_ms=50\n" +
                "last_down_ms=$lastDown\nlast_up_ms=${lastDown + 50}\nidle_breathing=false\n" + timing.provenance() +
                "baseline=mode/case update followed by real measure/layout and initial native draw\n" +
                "geometry=fixed touch-cell/text layout, baseline and paint measurements; actual appearance Canvas matrices and transformed centres captured during each exported frame in canvas-motion.csv\n" +
                "key_depth=DOWN seeds -0.5 signed position and approaches -1 at 48 rad/s; negative motion shrinks by up to 8% (4% on idle DOWN); UP preserves position/velocity and springs toward +0.75 with damping ratio 0.55 and natural frequency 20 rad/s, then switches at its analytic first positive peak to a zero-target critical tail at 10 rad/s; positive rebound scale gain is up to 3%, limited by real margins; ordinary taps peak about 188-202ms after UP and settle in about 1s; our design parameters, not Samsung internals\n" +
                "key_face_colour=full during press and positive rebound, then fades with the actual critical tail; legacy key_hold_ms/key_retreat_ms above are saved preferences, not this motion-driven face lifetime\n" +
                "popup_scale=1 throughout actual production alpha lifecycle\n" +
                "readability=same-time native frame difference with only mainText hidden, scanning the complete fixed touch cell; includes moved ink and fixed production outline\n" +
                "readable_pixel_threshold=channel contrast>=40 over at least 12 pixels\nminimum_readable_pixels=$minimumReadablePixels\n" +
                "release_max_adjacent_ink_channel_step=$inkStep (descriptive metric)\n" +
                "motion_metrics=fixed baseline black exterior gaps; all touched cells and their possible popup glyph rectangles excluded\n" +
                "frames_are_direct_native_draws=true\ninterpolation=false\n" +
                if (burst) "candidates=replay of real bundled Rime fixture; not live ARM engine execution\n" else "")
            assertEquals(input, typed.toString())
            if (burst) assertEquals(recorded.last()[1], adapter.candidates.first().text)
            val completeRest = samples.filter { it.first >= lastDown + timing.completeTail + 100L }
            assertTrue("The ${fps}fps review retains at least 300ms of complete black rest", completeRest.size >= fps * 300 / 1000)
            assertTrue("The stable sequence returns completely to black gaps after its tail",
                completeRest.all { it.second.gap.meanMaxRgb <= 3.0 && it.second.gap.colour30 == 0 })
            if (burst) assertTrue("The stable burst candidate strip returns to black",
                completeRest.all { it.second.candidate.meanMaxRgb <= 3.0 && it.second.candidate.colour30 == 0 })
            assertTrue("A visible field must survive outside the stable keycaps", samples.maxOf { it.second.gap.colour30 } >= 1000)
            val lastUp = lastDown + 50L
            assertTrue("The default field leaves a gentle visible tail after the last release",
                samples.any { it.first in lastUp + 350L..lastUp + 600L && it.second.gap.colour30 >= 500 })
            assertTrue("The default field clears at its configured lifetime after the last press",
                samples.filter { it.first >= lastDown + timing.waveTotal }.all {
                    it.second.gap.colour30 == 0 && it.second.candidate.colour30 == 0
                })
            val popupHeldFrame = samples.indexOfFirst { it.first >= 33L }
            val popupReleaseFrame = samples.indexOfFirst { it.first >= 100L }
            assertTrue("The real popup is readable while the first key is held/released",
                popupAlpha[popupHeldFrame] >= 0.75f && popupAlpha[popupReleaseFrame] > 0f)
            if (!burst) {
                val recoveredFrame = samples.indexOfFirst { it.first >= 150L }
                assertEquals("The real popup exits during cap recovery instead of lingering until 200ms",
                    0f, popupAlpha[recoveredFrame], 0f)
            }
            assertTrue("The last popup completes its release lifecycle", popupAlpha.last() == 0f)
            previews.stop()
            keyboard.onDetach()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun numberAndCategorySymbolPagesRenderTheirRealAndroidLayouts() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_InputViewTheme)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        controller.visible()
        advance(32)
        assertTrue(root.isAttachedToWindow && root.isShown)
        fun capture(keyboard: BaseKeyboard, name: String) {
            root.removeAllViews()
            root.addView(keyboard, FrameLayout.LayoutParams(600, 360))
            layout(root, 600, 360)
            save(render(root), name)
            keyboard.onDetach()
        }
        val number = NumberKeyboard(activity, ThemePreset.XuancaiBlackV09)
        var lastAction: KeyAction? = null
        number.keyActionListener = KeyActionListener { action, _ -> lastAction = action }
        val digits = keys(number).filter { (it.def as? KeyDef.Appearance.Text)?.displayText in (0..9).map(Int::toString) }
        assertEquals(10, digits.size)
        capture(number, "v15-number-nine-grid")
        val positions = digits.associate { (it.def as KeyDef.Appearance.Text).displayText to bounds(number, it) }
        assertTrue(positions.getValue("1").centerX() < positions.getValue("2").centerX())
        assertTrue(positions.getValue("2").centerX() < positions.getValue("3").centerX())
        assertEquals(positions.getValue("1").centerY(), positions.getValue("3").centerY())
        assertTrue(positions.getValue("4").centerY() > positions.getValue("1").centerY())
        assertTrue(positions.getValue("7").centerY() > positions.getValue("4").centerY())
        assertEquals(positions.getValue("2").centerX(), positions.getValue("0").centerX())
        assertEquals("", number.space.mainText.text.toString())
        assertNotNull(number.space.findViewWithTag<View>("space-key-icon"))
        val history = SymbolHistory(activity.getSharedPreferences("v15-symbol-history", Context.MODE_PRIVATE))
        for (symbol in listOf("，", "。", "？", "！", ".com", "@")) history.record(symbol)
        val chinese = SymbolKeyboard(activity, ThemePreset.XuancaiBlackV09,
            SymbolKeyboardState(SymbolCategory.Chinese), history)
        chinese.keyActionListener = KeyActionListener { action, _ -> lastAction = action }
        keys(chinese).first { (it.def as? KeyDef.Appearance.Text)?.displayText == "，" }.performClick()
        assertEquals(KeyAction.CommitAction("，"), lastAction)
        capture(chinese, "v15-symbols-chinese")
        val recent = SymbolKeyboard(activity, ThemePreset.XuancaiBlackV09,
            SymbolKeyboardState(SymbolCategory.Recent), history)
        assertTrue(keys(recent).any { (it.def as? KeyDef.Appearance.Text)?.displayText == ".com" })
        assertTrue(keys(recent).any { it.contentDescription == "上一页" })
        assertTrue(keys(recent).any { it.contentDescription == "下一页" })
        capture(recent, "v15-symbols-recent")
        for (page in 0..1) {
            val internet = SymbolKeyboard(activity, ThemePreset.XuancaiBlackV09,
                SymbolKeyboardState(SymbolCategory.Internet, page), history)
            capture(internet, if (page == 0) "v15-symbols-internet" else "v15-symbols-internet-page2")
            val longKeys = keys(internet).filterIsInstance<TextKeyView>().filter {
                (it.def as KeyDef.Appearance.Text).displayText.length > 3
            }
            assertTrue("The network page must exercise long suffixes and protocols", longKeys.isNotEmpty())
            for (key in longKeys) {
                val label = (key.def as KeyDef.Appearance.Text).displayText
                assertTrue("The full network text $label must fit inside its actual keycap",
                    key.mainText.paint.measureText(label) <= key.width - 2 * key.hMargin + 1f)
            }
        }
        controller.pause().stop().destroy()
    }
}
