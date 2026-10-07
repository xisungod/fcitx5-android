/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute

/** Real view shared by the settings fragment and narrow-screen rendering tests. */
class SettingsHomeUi(private val context: Context, private val open: (SettingsRoute) -> Unit) {
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val ink = color(R.color.ax_settings_text)
    private val secondary = color(R.color.ax_settings_secondary)
    private val accent = color(R.color.ax_settings_accent)
    private val surface = color(R.color.ax_settings_surface)

    data class Destination(val id: String, val title: Int, val summary: Int, val icon: Int,
        val route: SettingsRoute, val keywords: String = "")

    val destinations = listOf(
        Destination("theme", R.string.ax_settings_appearance, R.string.ax_settings_appearance_summary,
            R.drawable.ic_baseline_palette_24, SettingsRoute.Theme, "皮肤 壁纸 theme background"),
        Destination("effects", R.string.ax_settings_effects, R.string.ax_settings_effects_summary,
            R.drawable.ic_baseline_auto_awesome_24, SettingsRoute.LightEffects, "三星 水波 黏性 颜色 拖尾 sam ripple color"),
        Destination("layout", R.string.ax_settings_layout, R.string.ax_settings_layout_summary,
            R.drawable.ic_baseline_keyboard_24, SettingsRoute.VirtualKeyboard, "大写 气泡 弹窗 长按 滑动 尺寸 size uppercase popup gesture"),
        Destination("feedback", R.string.ax_settings_feedback, R.string.ax_settings_feedback_summary,
            R.drawable.ic_baseline_tune_24, SettingsRoute.Feedback, "振动 震感 触感 触觉 haptic vibration volume"),
        Destination("typing", R.string.ax_settings_typing, R.string.ax_settings_typing_summary,
            R.drawable.ic_baseline_list_alt_24, SettingsRoute.Typing, "词典 拼音 n l 模糊音 dictionary pinyin candidate"),
        Destination("typing-test", R.string.typing_test_title, R.string.typing_test_entry_summary,
            R.drawable.ic_baseline_keyboard_24, SettingsRoute.TypingTest, "测试 校准 准确度 邻键 test accuracy calibration"),
        Destination("touch-probe", R.string.touch_probe_self_check_title, R.string.touch_probe_self_check_summary,
            R.drawable.ic_baseline_info_24, SettingsRoute.TouchProbeSelfCheck, "触点 纠错 自检 版本 查询库 Rime 状态 查询 touch correction check status version library"),
        Destination("clipboard", R.string.clipboard, R.string.ax_settings_clipboard_summary,
            R.drawable.ic_clipboard, SettingsRoute.Clipboard, "复制 粘贴 短信 clipboard verification"),
        Destination("symbols", R.string.emoji_and_symbols, R.string.ax_settings_symbols_summary,
            R.drawable.ic_baseline_emoji_symbols_24, SettingsRoute.Symbol, "emoji symbols"),
        Destination("data", R.string.ax_settings_data, R.string.ax_settings_data_summary,
            R.drawable.ic_baseline_settings_backup_restore_24, SettingsRoute.Advanced, "导入 导出 恢复 backup restore compatibility"),
        Destination("tools", R.string.ax_settings_tools, R.string.ax_settings_tools_summary,
            R.drawable.ic_baseline_extension_24, SettingsRoute.EngineTools, "插件 开发 日志 外接键盘 language engine plugin debug"),
        Destination("about", R.string.ax_settings_about, R.string.ax_settings_about_summary,
            R.drawable.ic_baseline_info_24, SettingsRoute.About, "隐私 许可 版本 version privacy license")
    )

    private fun text(label: String, size: Float = 16f, tint: Int = ink) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(tint)
        includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun clickableBackground() = RippleDrawable(
        ColorStateList.valueOf(color(R.color.ax_settings_ripple)), shape(surface), shape(-1))

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
        tag = "axiang-settings-home"
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(color(R.color.ax_settings_background))
        addView(FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }, ViewGroup.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
    }
    val search = EditText(context).apply {
        id = View.generateViewId()
        tag = "settings-search"
        hint = context.getString(R.string.ax_settings_search)
        textSize = 15f
        setTextColor(ink)
        setHintTextColor(secondary)
        background = null
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(dp(8), 0, 0, 0)
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                true
            } else false
        }
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        body.addView(text("AXiang", 32f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -.03f
        })
        body.addView(text(context.getString(R.string.ax_settings_tagline), 14f, secondary),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(24) })
        val clear = ImageView(context).apply {
            tag = "settings-clear-search"
            setImageResource(R.drawable.ax_settings_close)
            imageTintList = ColorStateList.valueOf(secondary)
            contentDescription = context.getString(R.string.ax_settings_clear_search)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = clickableBackground()
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { search.text.clear() }
        }
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(surface, 14)
            setPadding(dp(14), 0, 0, 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_search_24)
                imageTintList = ColorStateList.valueOf(secondary)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(clear, LinearLayout.LayoutParams(dp(48), dp(52)))
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(content)
        render("")
        search.doAfterTextChanged {
            clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            render(it?.toString().orEmpty())
        }
    }

    fun matchingDestinations(query: String): List<Destination> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return destinations.filter { entry ->
            val searchable = "${context.getString(entry.title)} ${context.getString(entry.summary)} ${entry.keywords}".lowercase()
            words.all(searchable::contains)
        }
    }

    private fun section(title: Int) {
        content.addView(text(context.getString(title), 13f, secondary).apply {
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(10) })
    }

    private fun navigate(entry: Destination) {
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        open(entry.route)
    }

    private fun icon(entry: Destination) = ImageView(context).apply {
        setImageResource(entry.icon)
        imageTintList = ColorStateList.valueOf(accent)
        background = shape(color(R.color.ax_settings_accent_soft), 12)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row(entry: Destination) = LinearLayout(context).apply {
        tag = "settings-${entry.id}"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(78)
        setPadding(dp(16), dp(15), dp(14), dp(15))
        background = clickableBackground()
        isFocusable = true
        setOnClickListener { navigate(entry) }
        addView(icon(entry), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context.getString(entry.title), 16f))
            addView(text(context.getString(entry.summary), 12f, secondary).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ax_settings_chevron)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
    }

    private fun group(entries: List<Destination>) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface)
            entries.forEachIndexed { index, entry ->
                if (index > 0) addView(View(context).apply {
                    setBackgroundColor(color(R.color.ax_settings_divider))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(68); marginEnd = dp(16) })
                addView(row(entry), LinearLayout.LayoutParams(-1, -2))
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun render(query: String) {
        content.removeAllViews()
        if (query.isNotBlank()) {
            val matches = matchingDestinations(query)
            if (matches.isEmpty()) content.addView(text(context.getString(R.string.ax_settings_no_results), 15f, secondary).apply {
                tag = "settings-no-results"
                setPadding(dp(8), dp(32), dp(8), dp(32))
            }) else {
                section(R.string.ax_settings_title)
                group(matches)
            }
            return
        }
        section(R.string.ax_settings_daily)
        val appearance = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        destinations.take(2).forEachIndexed { index, entry ->
            appearance.addView(LinearLayout(context).apply {
                tag = "settings-${entry.id}"
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(18))
                background = clickableBackground()
                isFocusable = true
                setOnClickListener { navigate(entry) }
                addView(icon(entry), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(text(context.getString(entry.title), 17f).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                addView(text(context.getString(entry.summary), 12f, secondary),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(12) })
        }
        content.addView(appearance, LinearLayout.LayoutParams(-1, -2))
        section(R.string.ax_settings_input)
        group(destinations.subList(2, 9))
        section(R.string.ax_settings_general)
        group(destinations.subList(9, destinations.size))
        content.addView(text(context.getString(R.string.ax_settings_quick_tip), 12f, secondary).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(22), dp(12), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
}
