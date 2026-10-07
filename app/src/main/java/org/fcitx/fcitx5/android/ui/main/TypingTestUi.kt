/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.R

/** Fixed-target test UI. Measuring is owned by the explicit session, never by this view. */
class TypingTestUi(private val context: Context, private val actions: Actions) {
    data class Actions(
        val start: (Int) -> Unit,
        val complete: () -> Unit,
        val next: () -> Unit,
        val abort: () -> Unit,
        val export: () -> Unit,
        val clear: () -> Unit,
        val focusChanged: (Boolean) -> Unit,
        val retry: () -> Unit
    )
    enum class Phase { Intro, Typing, Completed, Report }
    data class State(
        val phase: Phase,
        val promptId: Int? = null,
        val target: String = "",
        val pinyin: String = "",
        val index: Int = 0,
        val total: Int = 0,
        val completed: Int = 0,
        val lastCommittedText: String? = null,
        val reportSummary: String? = null,
        val reportAvailable: Boolean = false,
        val failure: String? = null
    )

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val ink = color(R.color.ax_settings_text)
    private val secondary = color(R.color.ax_settings_secondary)
    private val accent = color(R.color.ax_settings_accent)
    private val surface = color(R.color.ax_settings_surface)
    private val primaryInk = if (ColorUtils.calculateContrast(Color.WHITE, accent) >= 4.5) Color.WHITE
        else color(R.color.ax_settings_background)
    private var rendered: State? = null
    private var reportActions: LinearLayout? = null
    private var resultText: TextView? = null
    private var status: TextView? = null
    private var pinyinShown = true
    var input: EditText? = null
        private set

    private val body = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(dp(720))
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(24))
        isFocusableInTouchMode = true
    }
    val root = ScrollView(context).apply {
        tag = "axiang-typing-test"
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(color(R.color.ax_settings_background))
        addView(FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }, ViewGroup.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            if (view.paddingBottom != bottom) {
                view.setPadding(0, 0, 0, bottom)
                input?.takeIf { it.hasFocus() }?.post {
                    input?.requestRectangleOnScreen(android.graphics.Rect(0, 0,
                        input?.width ?: 0, input?.height ?: 0), true)
                }
            }
            insets
        }
    }

    private fun shape(fill: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }
    private fun label(value: String, size: Float = 15f, tint: Int = ink) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(tint)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun label(id: Int, size: Float = 15f, tint: Int = ink) = label(context.getString(id), size, tint)
    private fun addText(view: TextView, top: Int = 0, bottom: Int = 0) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        })
    }
    private fun button(id: Int, tagName: String, primary: Boolean = false, click: () -> Unit) =
        AppCompatButton(context).apply {
            tag = tagName
            text = context.getString(id)
            textSize = 15f
            isAllCaps = false
            minimumHeight = dp(50)
            setTextColor(if (primary) primaryInk else accent)
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.ax_settings_ripple)),
                shape(if (primary) accent else surface, 14), shape(Color.WHITE, 14))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { click() }
        }
    private fun addButton(id: Int, tag: String, primary: Boolean = false, click: () -> Unit) {
        body.addView(button(id, tag, primary, click), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
    }
    private fun heading(id: Int) = label(id, 27f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = -.02f
    }
    private fun note(id: Int) = label(id, 13f, secondary)

    fun render(state: State) {
        val previous = rendered
        rendered = state
        // Keep the actual InputConnection alive for routine session updates.
        if (previous?.phase == state.phase && previous.promptId == state.promptId) {
            status?.apply {
                text = state.failure.orEmpty()
                visibility = if (state.failure.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            reportActions?.visibility = if (state.reportAvailable) View.VISIBLE else View.GONE
            resultText?.text = state.reportSummary ?: context.getString(R.string.typing_test_result_no_samples)
            return
        }
        input?.clearFocus()
        input = null
        status = null
        reportActions = null
        resultText = null
        body.removeAllViews()
        root.scrollTo(0, 0)
        when (state.phase) {
            Phase.Intro -> showIntro(state)
            Phase.Typing -> showTyping(state)
            Phase.Completed -> showCompleted(state)
            Phase.Report -> showReport(state)
        }
        if (state.phase == Phase.Typing) {
            status = label(state.failure.orEmpty(), 13f, secondary).apply {
                tag = "typing-test-status"
                visibility = if (state.failure.isNullOrBlank()) View.GONE else View.VISIBLE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { addText(it, top = 12) }
        }
    }

    private fun showIntro(state: State) {
        addText(heading(R.string.typing_test_hero))
        addText(label(R.string.typing_test_intro, 15f, secondary), 12, 12)
        addButton(R.string.typing_test_quick, "typing-test-start-quick", true) { actions.start(5) }
        addText(note(R.string.typing_test_quick_summary), 8, 4)
        addButton(R.string.typing_test_full, "typing-test-start-full") { actions.start(20) }
        addText(note(R.string.typing_test_full_summary), 8, 16)
        addText(note(R.string.typing_test_privacy), 12, 8)
        addReportActions(state.reportAvailable)
    }

    private fun addTarget(state: State) {
        addText(label(context.getString(R.string.typing_test_progress, state.index + 1, state.total),
            13f, secondary), bottom = 12)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = shape(surface)
            addView(label(R.string.typing_test_target_label, 12f, secondary))
            addView(label(state.target, 25f).apply {
                tag = "typing-test-target"
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val pinyin = label(state.pinyin, 14f, secondary).apply {
                tag = "typing-test-pinyin"
                visibility = if (pinyinShown) View.VISIBLE else View.GONE
            }
            val toggle = button(if (pinyinShown) R.string.typing_test_hide_pinyin else R.string.typing_test_show_pinyin,
                "typing-test-pinyin-toggle") {}
            toggle.setOnClickListener {
                pinyinShown = !pinyinShown
                pinyin.visibility = if (pinyinShown) View.VISIBLE else View.GONE
                toggle.setText(if (pinyinShown) R.string.typing_test_hide_pinyin else R.string.typing_test_show_pinyin)
            }
            addView(pinyin, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showTyping(state: State) {
        addTarget(state)
        val edit = EditText(context).apply {
            id = R.id.typing_test_input
            tag = "typing-test-input"
            hint = context.getString(R.string.typing_test_input_hint)
            contentDescription = context.getString(R.string.typing_test_input_description)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // NO_PERSONALIZED_LEARNING disables touch.2's evidence gate. Do not set it here.
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 2
            maxLines = 4
            textSize = 20f
            setTextColor(ink)
            setHintTextColor(secondary)
            background = shape(surface, 14)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnFocusChangeListener { _, focused -> actions.focusChanged(focused) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { actions.complete(); true } else false
            }
        }
        input = edit
        body.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        addText(note(R.string.typing_test_input_help), 10)
        addButton(R.string.typing_test_complete, "typing-test-complete", true, actions.complete)
        addButton(R.string.typing_test_abort, "typing-test-abort", click = actions.abort)
        addText(note(R.string.typing_test_paused), 12)
    }

    private fun showCompleted(state: State) {
        addTarget(state)
        addText(label(R.string.typing_test_review_label, 13f, secondary), 20, 8)
        addText(label(state.lastCommittedText.orEmpty(), 21f).apply {
            tag = "typing-test-completed-text"
            setTextIsSelectable(true)
        })
        addText(note(R.string.typing_test_review_help), 12, 8)
        addButton(if (state.index + 1 >= state.total) R.string.typing_test_finish else R.string.typing_test_next,
            "typing-test-next", true, actions.next)
        addButton(R.string.typing_test_abort, "typing-test-abort", click = actions.abort)
    }

    private fun showReport(state: State) {
        addText(heading(R.string.typing_test_result_title))
        addText(label(context.getString(R.string.typing_test_completed_count, state.completed),
            14f, secondary), 10)
        addText(note(R.string.typing_test_result_intro), 10, 16)
        resultText = label(state.reportSummary ?: context.getString(R.string.typing_test_result_no_samples),
            16f).apply {
            tag = "typing-test-results"
            background = shape(surface)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setTextIsSelectable(true)
        }.also { addText(it) }
        addText(note(R.string.typing_test_unavailable_help), 14)
        addText(note(R.string.typing_test_alternative_help), 10)
        addText(note(R.string.typing_test_result_limitation), 10, 8)
        addReportActions(state.reportAvailable)
        addButton(R.string.typing_test_try_again, "typing-test-retry", true, actions.retry)
    }

    private fun addReportActions(available: Boolean) {
        reportActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (available) View.VISIBLE else View.GONE
            addView(button(R.string.typing_test_export, "typing-test-export", click = actions.export),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(button(R.string.typing_test_clear, "typing-test-clear", click = actions.clear),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }.also { body.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }
}
