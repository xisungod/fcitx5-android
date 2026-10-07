/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.candidateCommentForeground
import org.fcitx.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateCommentRenderingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val theme = ThemePreset.XuancaiBlackV09
    private var previousApplication: FcitxApplication? = null

    @Before fun prepare() {
        val instance = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = instance.get(null) as? FcitxApplication
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        instance.set(null, app)
        AppPrefs.init(context.getSharedPreferences("candidate-comment-rendering", Context.MODE_PRIVATE))
    }

    @After fun restoreApplication() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
    }

    private fun render(view: View, width: Int = 360, height: Int = 52): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(Color.BLACK)
            repeat(2) { view.draw(canvas) }
        }
    }

    private fun bounds(bitmap: Bitmap, color: Int): Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            // Constrained text is scaled by Canvas. Its thin strokes may contain
            // only antialiased pixels, so compare color ratios over the black backdrop.
            if (Color.red(pixel) >= 50 &&
                kotlin.math.abs(Color.red(pixel) * Color.blue(color) -
                    Color.blue(pixel) * Color.red(color)) <= Color.blue(color) + Color.red(color) &&
                kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 1) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("No rendered pixels for ${Integer.toHexString(color)}", right >= left && bottom >= top)
        return Rect(left, top, right + 1, bottom + 1)
    }

    @Test fun commentsActuallyDrawSmallerAndInTheThemeCommentColorWithoutShrinkingChinese() {
        val plain = CandidateItemUi(context, theme).apply {
            updateCandidate(CandidateWord("", "你是啊", ""))
        }
        val annotated = CandidateItemUi(context, theme).apply {
            updateCandidate(CandidateWord("", "你是啊", "ni shi a"))
        }
        val plainImage = render(plain.root)
        val image = render(annotated.root)
        try {
            val plainBody = bounds(plainImage, theme.candidateTextColor)
            val body = bounds(image, theme.candidateTextColor)
            val hint = bounds(image, theme.candidateCommentColor)
            assertEquals(plainBody.width(), body.width())
            assertEquals(plainBody.height(), body.height())
            assertTrue("hint is smaller than primary text", hint.height() < body.height())
            assertTrue("hint follows the primary text", hint.left > body.right)
            assertEquals(24f, (annotated.root.getChildAt(0) as TextView).textSize, .01f)
        } finally { plainImage.recycle(); image.recycle() }
    }

    @Test fun smallerHintsKeepNormalTextContrastOnKnownLightSurfacesAndPreserveTheDarkTheme() {
        val light = ThemePreset.MaterialLight
        assertTrue(ColorUtils.calculateContrast(light.candidateCommentColor, light.barColor) < 4.5)
        val adjusted = candidateCommentForeground(light)
        assertTrue(ColorUtils.calculateContrast(adjusted, light.barColor) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(adjusted, light.backgroundColor) >= 4.5)
        assertNotEquals(light.candidateCommentColor, adjusted)
        assertNotEquals("secondary hints keep their own tone", light.candidateTextColor, adjusted)
        val floating = candidateCommentForeground(light, floating = true)
        assertTrue(ColorUtils.calculateContrast(floating, light.backgroundColor) >= 4.5)
        assertEquals(theme.candidateCommentColor, candidateCommentForeground(theme))
        // The translucent bar is drawn over unknown content; no false contrast guarantee.
        assertEquals(ThemePreset.TransparentDark.candidateCommentColor,
            candidateCommentForeground(ThemePreset.TransparentDark))
    }

    @Test fun nativeCorrectionCommentsAndTouchMarksBothUseTheSmallRaisedMarker() {
        for (comment in listOf("*", "纠错", "糾錯")) {
            val ui = CandidateItemUi(context, theme).apply {
                updateCandidate(CandidateWord("", "几号啊", comment, false))
            }
            val image = render(ui.root)
            try {
                val body = bounds(image, theme.candidateTextColor)
                val mark = bounds(image, theme.candidateCommentColor)
                assertTrue(mark.height() < body.height())
                assertTrue(mark.bottom < body.bottom)
                assertEquals("几号啊*", (ui.root.getChildAt(0) as TextView).text.toString())
                assertEquals(52, ui.root.height)
            } finally { image.recycle() }
        }
    }

    @Test fun floatingAndConstrainedExpandedSlotsRenderTheSameAnnotations() {
        val word = CandidateWord("", "你是啊", "ni shi a")
        val floating = LabeledCandidateItemUi(context, theme) {
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            isSingleLine = true
        }.apply { update(word, active = false) }
        // The production floating item is a RecyclerView child. Native TextView
        // drawing also needs its actual attached parent rather than a detached root.
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val host = FrameLayout(controller.get()).apply {
            addView(floating.root, FrameLayout.LayoutParams(-1, -1))
        }
        controller.get().setContentView(host)
        shadowOf(Looper.getMainLooper()).idle()
        val floatingImage = render(host)
        assertEquals("你是啊 ni shi a", floating.root.text.toString())
        assertNotNull(floating.root.layout)
        assertEquals(1, floating.root.lineCount)
        val expanded = CandidateItemUi(context, theme).apply { updateCandidate(word) }
        val expandedImage = render(expanded.root, width = 112)
        try {
            for ((name, bitmap) in listOf("floating-annotations.png" to floatingImage,
                "expanded-annotations-narrow.png" to expandedImage)) {
                File("build/outputs/candidate-checks").apply { mkdirs() }.resolve(name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue(bounds(floatingImage, theme.candidateCommentColor).height() <
                bounds(floatingImage, theme.candidateTextColor).height())
            assertTrue(bounds(expandedImage, theme.candidateCommentColor).height() <
                bounds(expandedImage, theme.candidateTextColor).height())
            assertTrue(bounds(expandedImage, theme.candidateTextColor).left >= 0)
            assertTrue(bounds(expandedImage, theme.candidateCommentColor).right <= 112)
        } finally {
            floatingImage.recycle(); expandedImage.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test fun supplementaryCharactersAndMixedDirectionWordsKeepTheirWholePrimaryTextBounds() {
        for (word in listOf("你𠮷好 😊", "测试abc", "abc אבג 测试")) {
            val ui = CandidateItemUi(context, theme).apply {
                updateCandidate(CandidateWord("", word, "ni hao"))
            }
            val image = render(ui.root)
            try {
                val body = Rect()
                assertTrue(ui.mainTextBounds(body))
                assertTrue(body.left >= 0 && body.right <= ui.root.width)
                assertTrue(body.top >= 0 && body.bottom <= ui.root.height)
                assertTrue(bounds(image, theme.candidateCommentColor).height() > 0)
                assertEquals("$word ni hao", (ui.root.getChildAt(0) as TextView).text.toString())
            } finally { image.recycle() }
        }
    }

    @Test fun largeAccessibilityFontsStillKeepCommentHierarchyAndSaveARealRenderingPreview() {
        RuntimeEnvironment.setFontScale(1.4f)
        val word = CandidateWord("", "你是啊", "ni shi a")
        val after = CandidateItemUi(context, theme).apply { updateCandidate(word) }
        val image = render(after.root)
        try {
            assertTrue(bounds(image, theme.candidateCommentColor).height() <
                bounds(image, theme.candidateTextColor).height())
            assertTrue((after.root.getChildAt(0) as TextView).textSize > 24f)
            File("build/outputs/candidate-checks").apply { mkdirs() }
                .resolve("candidate-annotation-font-1.4.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
        } finally { image.recycle(); RuntimeEnvironment.setFontScale(1f) }

        val preview = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(AutoScaleTextView(context).apply {
                text = "几号啊*    你是啊 ni shi a"
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                setTextColor(theme.candidateTextColor)
            }, LinearLayout.LayoutParams(-1, 52))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(CandidateWord("", "几号啊", "*", false), word).forEach {
                    addView(CandidateItemUi(context, theme).apply { updateCandidate(it) }.root,
                        LinearLayout.LayoutParams(0, 52, 1f))
                }
            }, LinearLayout.LayoutParams(-1, 52))
        }
        val previewImage = render(preview, height = 104)
        try {
            File("build/outputs/candidate-checks").apply { mkdirs() }
                .resolve("candidate-annotations-before-after.png").outputStream().use {
                    previewImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
        } finally { previewImage.recycle() }
    }
}
