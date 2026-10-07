/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.ui.CandidateUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateEntry
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapFeedback
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
class PinyinTouchCandidateUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousApplication: FcitxApplication? = null

    @Before fun prepare() {
        val instance = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApplication = instance.get(null) as? FcitxApplication
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        instance.set(null, app)
        AppPrefs.init(context.getSharedPreferences("pinyin-touch-candidate-ui", Context.MODE_PRIVATE))
    }

    @After fun restoreApplication() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, previousApplication)
        }
    }

    private fun buttons(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun word(text: String) = CandidateWord("", text, "")
    private fun offer(token: Long = 17, text: String = "经常会") =
        PinyinTouchCandidateOffer(token, text, "jibgchsnghui", "jingchanghui")

    private class Host : AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val adapter = HorizontalCandidateViewAdapter(ThemePreset.XuancaiBlackV09)
        val view = RecyclerView(activity).apply {
            id = View.generateViewId()
            itemAnimator = null
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            adapter = this@Host.adapter
        }
        init { activity.setContentView(view) }
        fun layout(width: Int = 1000) {
            repeat(2) {
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, 52)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        fun item(position: Int) = view.findViewHolderForAdapterPosition(position)!!.itemView
        override fun close() { controller.pause().stop().destroy() }
    }

    @Test fun syntheticSecondSlotUsesTokenAndRawFollowingItemUsesOriginalEngineIndex() {
        Host().use { host ->
            val selectedRaw = mutableListOf<Int>()
            val selectedTouch = mutableListOf<Long>()
            host.adapter.onRawSelect = { selectedRaw += it }
            host.adapter.onTouchSelect = { selectedTouch += it }
            host.adapter.updateCandidates(arrayOf(word("基本"), word("经常会"), word("检查")), 90)
            host.adapter.setTouchCandidate(offer())
            host.layout()
            assertEquals(3, host.adapter.itemCount)
            assertEquals(90, host.adapter.total)
            assertEquals("基本", buttons(host.item(0)).single().text.toString())
            assertEquals("经常会*", buttons(host.item(1)).single().text.toString())
            host.item(0).performClick()
            host.item(1).performClick()
            host.item(2).performClick()
            assertEquals(listOf(0, 2), selectedRaw)
            assertEquals(listOf(17L), selectedTouch)
            assertEquals(listOf("基本", "经常会", "检查"), host.adapter.candidates.map { it.text })
        }
    }

    @Test fun promotedFirstSlotKeepsLiteralSelectableAndUsesTheTouchToken() {
        Host().use { host ->
            val selectedRaw = mutableListOf<Int>()
            val selectedTouch = mutableListOf<Long>()
            host.adapter.onRawSelect = { selectedRaw += it }
            host.adapter.onTouchSelect = { selectedTouch += it }
            host.adapter.updateCandidates(arrayOf(word("基本"), word("经常会"), word("检查")), 90)
            host.adapter.setTouchCandidate(offer().copy(promotedToFirst = true))
            host.layout()
            assertEquals(listOf("经常会*", "基本", "检查"),
                (0 until host.adapter.itemCount).map { buttons(host.item(it)).single().text.toString() })
            assertEquals(context.getString(R.string.pinyin_touch_alternative_description, "经常会"),
                host.item(0).contentDescription.toString())
            assertFalse(host.item(0).isLongClickable)
            host.item(0).performClick()
            host.item(1).performClick()
            host.item(2).performClick()
            assertEquals(listOf(17L), selectedTouch)
            assertEquals(listOf(0, 2), selectedRaw)
            assertEquals(listOf("基本", "经常会", "检查"), host.adapter.candidates.map { it.text })
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun visibilityRequiresCompleteChineseButAllowsAnOffscreenSpellingHint() {
        Host().use { host ->
            host.adapter.updateCandidates(arrayOf(CandidateWord("", "你是啊", "ni shi a")), 1)
            host.layout(width = 80)
            val holder = host.view.findViewHolderForAdapterPosition(0) as CandidateViewHolder
            val visibleText = Rect()
            val body = Rect()
            assertTrue(holder.ui.visibleTextBounds(visibleText))
            assertTrue(holder.ui.mainTextBounds(body))
            assertFalse("a partial primary word cannot score: visible=$visibleText body=$body viewport=${host.view.width}",
                visibleText.contains(body))
            host.layout(width = 120)
            assertTrue(holder.ui.visibleTextBounds(visibleText))
            assertTrue(holder.ui.mainTextBounds(body))
            assertTrue("the complete primary word can score even when its spelling hint is clipped",
                visibleText.contains(body))
            assertTrue(holder.itemView.width > host.view.width)
        }
    }

    @Test fun touchSlotCannotOpenNativeCandidateMenuAndRecycledRawRestoresLongClick() {
        Host().use { host ->
            val longClicked = mutableListOf<Int>()
            host.adapter.onRawLongClick = { index, _, _ -> longClicked += index }
            host.adapter.updateCandidates(arrayOf(word("基本"), word("经常会")), 2)
            host.adapter.setTouchCandidate(offer())
            host.layout()
            assertFalse(host.item(1).isLongClickable)
            assertFalse(host.item(1).performLongClick())
            assertTrue(longClicked.isEmpty())
            assertEquals(context.getString(R.string.pinyin_touch_alternative_description, "经常会"),
                host.item(1).contentDescription.toString())
            host.adapter.setTouchCandidate(null)
            host.layout()
            assertTrue(host.item(1).isLongClickable)
            host.item(1).performLongClick()
            assertEquals(listOf(1), longClicked)
            assertNull(host.item(1).contentDescription)
        }
    }

    @Test fun offerChangeAndNativeCandidateUpdateRejectBoundOldClick() {
        Host().use { host ->
            val selected = mutableListOf<Long>()
            host.adapter.onTouchSelect = { selected += it }
            val words = arrayOf(word("基本"), word("检查"))
            host.adapter.updateCandidates(words, 2)
            host.adapter.setTouchCandidate(offer())
            host.layout()
            val oldView = host.item(2)
            val oldBinding = host.adapter.binding(2)
            val oldHolder = host.view.findViewHolderForAdapterPosition(2)!!
            assertEquals(oldBinding, host.adapter.currentBinding(oldHolder))
            host.adapter.setTouchCandidate(offer(83, "你好啊"))
            assertFalse(host.adapter.isCurrent(oldBinding, 2))
            assertNull(host.adapter.currentBinding(oldHolder))
            oldView.performClick()
            assertTrue(selected.isEmpty())
            host.layout()
            assertEquals(host.adapter.binding(2), host.adapter.currentBinding(
                host.view.findViewHolderForAdapterPosition(2)!!))
            host.item(2).performClick()
            assertEquals(listOf(83L), selected)
            val newerBinding = host.adapter.binding(2)
            val newerView = host.item(2)
            host.adapter.updateCandidates(words, 2)
            assertFalse(host.adapter.isCurrent(newerBinding, 2))
            newerView.performClick()
            assertEquals(listOf(83L), selected)
            host.adapter.setTouchCandidate(null)
            host.layout()
            assertFalse(host.adapter.entries.any { it is HorizontalCandidateEntry.Touch })
        }
    }

    @Test fun paginationAppendsRawWordsWithoutShiftingTheirNativeSelectionIndices() {
        Host().use { host ->
            val selected = mutableListOf<Int>()
            host.adapter.onRawSelect = { selected += it }
            host.adapter.updateCandidates(arrayOf(word("基本"), word("检查")), 100)
            host.adapter.setTouchCandidate(offer())
            host.adapter.appendCandidates(arrayOf(word("经常会"), word("机场")), 100)
            host.layout()
            assertEquals(4, host.adapter.candidates.size)
            assertEquals(100, host.adapter.total)
            assertEquals(listOf(0, 1, 3), host.adapter.entries.filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.nativeIndex })
            host.item(3).performClick()
            assertEquals(listOf(3), selected)
        }
    }

    @Test fun novelSuggestionLeavesOriginalTargetSecondAndUsesSeparateFourthSlotToken() {
        Host().use { host ->
            val rawSelected = mutableListOf<Int>()
            val touchSelected = mutableListOf<Long>()
            host.adapter.onRawSelect = { rawSelected += it }
            host.adapter.onTouchSelect = { touchSelected += it }
            host.adapter.updateCandidates(arrayOf(word("第一"), word("目标"), word("第三"), word("尾部")), 4)
            host.adapter.setTouchCandidate(offer(text = "错误"))
            host.layout()
            assertEquals("目标", buttons(host.item(1)).single().text.toString())
            assertEquals("第三", buttons(host.item(2)).single().text.toString())
            assertEquals("错误*", buttons(host.item(3)).single().text.toString())
            host.item(1).performClick()
            host.item(3).performClick()
            assertEquals(listOf(1), rawSelected)
            assertEquals(listOf(17L), touchSelected)
        }
    }

    @Test fun longAlternativeStaysInsideScrollableRowWithoutCreatingSeparateFeedbackChip() {
        Host().use { host ->
            val text = "这是一条很长的纠错候选用于检查窄屏显示"
            host.adapter.updateCandidates(arrayOf(word("基本"), word("检")), 2)
            host.adapter.setTouchCandidate(offer(text = text))
            (host.view.parent as ViewGroup).removeView(host.view)
            val ui = CandidateUi(host.activity, ThemePreset.XuancaiBlackV09, host.view)
            host.activity.setContentView(ui.root)
            for (width in listOf(192, 240, 360, 600)) {
                repeat(2) {
                    ui.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY))
                    ui.root.layout(0, 0, width, 52)
                    shadowOf(Looper.getMainLooper()).idle()
                }
                assertEquals(width - 48, host.view.width)
                assertEquals(52, ui.root.height)
                assertEquals("基本", buttons(host.item(0)).single().text.toString())
                assertEquals(1, buttons(ui.root).count { it.text.toString() == "$text*" })
            }
        }
    }

    @Test fun dismissingInlineFeedbackClearsHiddenCallbacksAndReleasesCandidateWidth() {
        val literalCandidates = View(context).apply { id = View.generateViewId() }
        val ui = CandidateUi(context, ThemePreset.XuancaiBlackV09, literalCandidates)
        var restored: Long? = null
        ui.setPinyinFeedback(PinyinTapFeedback(4, 'b', 'n'), { restored = it }, {})
        val restore = buttons(ui.root).single { it.text.contains("b") }
        ui.setPinyinFeedback(null, {}, {})
        restore.performClick()
        assertNull(restored)
        assertEquals(View.GONE, restore.visibility)
        ui.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY))
        ui.root.layout(0, 0, 360, 52)
        assertEquals(312, literalCandidates.width)
    }
}
