package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.fcitx.fcitx5.android.input.keyboard.IdleGlowPainter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdleGlowDirectionTest {
    @Test fun lightStartsAtBothEdgesAndReachesTheCentreBeforeFading() {
        val painter = IdleGlowPainter(0xff00f0ff.toInt(), 0xffbe38ff.toInt())
        fun frame(phase: Float): Bitmap {
            val image = Bitmap.createBitmap(600, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.BLACK)
            painter.draw(canvas, 600, 240, 0.28f, phase)
            return image
        }
        val early = frame(0.05f)
        val wide = frame(0.5f)
        assertTrue(Color.blue(early.getPixel(2, 120)) > 40)
        assertTrue(Color.blue(early.getPixel(598, 120)) > 40)
        assertEquals(Color.BLACK, early.getPixel(300, 120))
        assertTrue(Color.blue(wide.getPixel(300, 120)) > 20)
        assertTrue(Color.blue(wide.getPixel(200, 120)) > Color.blue(early.getPixel(200, 120)))
        for ((name, image) in listOf("idle-sides-start" to early, "idle-sides-merged" to wide)) {
            val file = File("build/outputs/effect-checks/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
