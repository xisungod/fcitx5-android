/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/** Previews are actual Android drawables rendered by Skia, not SVG approximations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconBrandingTest {
    private fun drawable(id: Int): Drawable =
        requireNotNull(RuntimeEnvironment.getApplication().getDrawable(id)).mutate()

    private fun render(id: Int, size: Int = 432): Bitmap {
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable(id).apply {
            setBounds(0, 0, size, size)
            draw(Canvas(image))
        }
        return image
    }

    private fun save(image: Bitmap, name: String) {
        val destination = File("build/outputs/icon-checks/$name.png")
        destination.parentFile.mkdirs()
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun adaptiveDebugReleaseAndCompatibilityIconsUseOneBrandAndExportNativePreviews() {
        assertTrue(drawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(drawable(R.mipmap.ic_launcher_debug) is AdaptiveIconDrawable)
        val normal = render(R.mipmap.ic_launcher)
        val debug = render(R.mipmap.ic_launcher_debug)
        val round = render(R.mipmap.ic_launcher_round)
        val roundDebug = render(R.mipmap.ic_launcher_round_debug)
        val installed = render(R.mipmap.app_icon)
        assertTrue("Debug distribution must show the same Axiang mark", normal.sameAs(debug))
        assertTrue(round.sameAs(roundDebug))
        assertTrue("The icon selected by the installed build must be the new mark", normal.sameAs(installed))
        val legacy = render(R.drawable.ic_launcher_legacy)
        val legacyRound = render(R.drawable.ic_launcher_legacy_round)
        assertEquals("Legacy corners must remain transparent", 0, Color.alpha(legacy.getPixel(0, 0)))
        assertTrue("Legacy background remains opaque behind the mark", Color.alpha(legacy.getPixel(216, 216)) == 255)

        val mono = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val monoCanvas = Canvas(mono)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 34, 52) }
        monoCanvas.drawCircle(216f, 216f, 216f, circle)
        drawable(R.drawable.ic_launcher_foreground_monochrome).apply {
            setTint(Color.rgb(163, 226, 255))
            // Adaptive icon foreground is cropped from 108 units to the central 72.
            setBounds(-108, -108, 540, 540)
            draw(monoCanvas)
        }
        val images = listOf(normal, legacy, legacyRound, mono)
        val names = listOf("adaptive", "legacy-square", "legacy-round", "themed-monochrome")
        val board = Bitmap.createBitmap(1280, 430, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(240, 244, 250))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(39, 51, 72)
            textSize = 19f
            textAlign = Paint.Align.CENTER
        }
        images.forEachIndexed { index, image ->
            save(image, "axiang-${names[index]}")
            val x = index * 320f
            canvas.drawBitmap(image, null, android.graphics.RectF(x + 36, 24f, x + 284, 272f), null)
            canvas.drawText(names[index], x + 160, 307f, label)
            canvas.drawBitmap(image, null, android.graphics.RectF(x + 103, 335f, x + 151, 383f), null)
            canvas.drawBitmap(image, null, android.graphics.RectF(x + 171, 329f, x + 231, 389f), null)
        }
        save(board, "axiang-native-review")
        (images + listOf(debug, round, roundDebug, installed, board)).forEach(Bitmap::recycle)
    }

    @Test
    fun foregroundKeepsAClearCounterKeyboardCueAndAdaptiveSafeArea() {
        val image = render(R.drawable.ic_launcher_foreground)
        var cyan = 0
        var violet = 0
        var maximumRadius = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image.getPixel(x, y)
            if (Color.alpha(color) < 128) continue
            maximumRadius = maxOf(maximumRadius, hypot((x + 0.5) / 4.0 - 54.0, (y + 0.5) / 4.0 - 54.0))
            if (Color.green(color) > 160 && Color.blue(color) > 190 && Color.red(color) < 120) cyan++
            if (Color.blue(color) > 180 && Color.red(color) > 80 && Color.green(color) < 150) violet++
        }
        assertTrue("The complete symbol fits the adaptive 66-unit safe circle", maximumRadius < 33.0)
        assertTrue("Cyan facet remains visible at launcher scale", cyan > 200)
        assertTrue("Violet facet remains visible at launcher scale", violet > 200)
        assertEquals("A clear counter makes the A legible", 0, Color.alpha(image.getPixel(54 * 4, 47 * 4)))
        assertTrue("The space key survives at the bottom of the mark", Color.alpha(image.getPixel(54 * 4, 74 * 4)) > 200)
        image.recycle()

        val mono = render(R.drawable.ic_launcher_foreground_monochrome)
        val monoDebug = render(R.drawable.ic_launcher_foreground_monochrome_debug)
        assertTrue("Themed debug and release icons have the same silhouette", mono.sameAs(monoDebug))
        for (y in 0 until mono.height) for (x in 0 until mono.width) {
            val color = mono.getPixel(x, y)
            if (Color.alpha(color) > 0) assertEquals("The launcher supplies monochrome tint", Color.WHITE and 0x00ffffff, color and 0x00ffffff)
        }
        mono.recycle()
        monoDebug.recycle()
    }

    @Test
    @Config(sdk = [23])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun api23LoadsEveryLegacyLauncherResourceWithoutAdaptiveOrGradientSupport() {
        for (id in intArrayOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_debug,
            R.mipmap.ic_launcher_round, R.mipmap.ic_launcher_round_debug, R.mipmap.app_icon)) {
            val icon = drawable(id)
            assertEquals("API 23 must resolve the native vector compatibility layer", "LayerDrawable", icon.javaClass.simpleName)
            assertTrue(icon.intrinsicWidth > 0)
            assertTrue(icon.intrinsicHeight > 0)
        }
    }
}
