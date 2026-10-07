/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RimeTouchProbe
import org.fcitx.fcitx5.android.core.RimeTouchProbeStatus
import org.fcitx.fcitx5.android.ui.common.AXiangSettingsPalette
import java.util.Locale

/** A local, cached health snapshot. Opening this page does not initialize or query Rime. */
internal class TouchProbeSelfCheckUi(private val context: Context, check: () -> Unit) {
    private val palette = AXiangSettingsPalette.from(context)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun text(value: String = "", size: Float = 15f, secondary: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(if (secondary) palette.secondary else palette.text)
            includeFontPadding = false
            setLineSpacing(dp(3).toFloat(), 1f)
        }
    private fun title(id: Int) = text(context.getString(id), 13f, true)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(28))
    }
    val root = ScrollView(context).apply {
        tag = "touch-probe-self-check"
        setBackgroundColor(palette.background)
        addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
    }
    private val outcome = text(size = 21f).apply {
        tag = "touch-probe-query-outcome"
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val version = text().apply { tag = "touch-probe-version" }
    private val library = text().apply { tag = "touch-probe-library" }
    private val interfaceStatus = text().apply { tag = "touch-probe-interface" }
    private val source = text(size = 12f, secondary = true)
    private val lastSuccess = text(size = 12f, secondary = true).apply {
        tag = "touch-probe-last-native-success"
    }
    private val timing = text(size = 14f).apply { tag = "touch-probe-timing" }
    private val counts = text(size = 13f, secondary = true)
    private val failure = text(size = 13f, secondary = true)
    private val notice = text(size = 14f, secondary = true).apply {
        tag = "touch-probe-check-notice"
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val checkButton = AppCompatButton(context).apply {
        tag = "touch-probe-check-button"
        setText(R.string.touch_probe_check_now)
        textSize = 15f
        isAllCaps = false
        backgroundTintList = null
        setTextColor(if (ColorUtils.calculateContrast(Color.WHITE, palette.accent) >= 4.5)
            Color.WHITE else palette.background)
        background = RippleDrawable(ColorStateList.valueOf(palette.ripple),
            GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = dp(14).toFloat()
            }, null)
        minimumHeight = dp(48)
        setOnClickListener { check() }
    }

    init {
        content.addView(text(context.getString(R.string.touch_probe_self_check_title), 25f))
        content.addView(text(context.getString(R.string.touch_probe_self_check_description), 14f, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(20) })
        card {
            addView(title(R.string.touch_probe_last_query))
            addView(outcome, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(lastSuccess, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(failure, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        card {
            addView(version)
            addView(library, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(interfaceStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        card {
            addView(title(R.string.touch_probe_timing_title))
            addView(timing, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(counts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        content.addView(checkButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        content.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(text(context.getString(R.string.touch_probe_readonly_note), 12f, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        render(RimeTouchProbe.Diagnostics(null, null, null))
    }

    private fun card(build: LinearLayout.() -> Unit) {
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(18).toFloat()
            }
            build()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    fun setChecking(checking: Boolean) {
        checkButton.isEnabled = !checking
        checkButton.setText(if (checking) R.string.touch_probe_checking else R.string.touch_probe_check_now)
        if (checking) notice.setText(R.string.touch_probe_checking_note)
    }

    fun setNotice(message: Int?) {
        notice.text = message?.let(context::getString).orEmpty()
    }

    fun render(snapshot: RimeTouchProbe.Diagnostics) {
        val unknown = context.getString(R.string.touch_probe_unknown)
        outcome.setText(when (snapshot.lastOutcome) {
            "Success" -> R.string.touch_probe_query_success
            "NoCandidates" -> R.string.touch_probe_query_empty
            "Timeout" -> R.string.touch_probe_query_timeout
            "Unavailable" -> R.string.touch_probe_query_unavailable
            else -> R.string.touch_probe_query_not_run
        })
        version.text = context.getString(R.string.touch_probe_runtime_version,
            snapshot.runtimeStatus?.runtimeVersion ?: unknown, RimeTouchProbeStatus.EXPECTED_VERSION)
        library.text = context.getString(R.string.touch_probe_library_status, context.getString(
            when (snapshot.libraryAvailable) {
                true -> R.string.touch_probe_available
                false -> R.string.touch_probe_unavailable
                null -> R.string.touch_probe_unknown
            }))
        interfaceStatus.text = context.getString(R.string.touch_probe_interface_status,
            if (snapshot.runtimeStatus?.code == "Ready")
                context.getString(if (snapshot.readyHandle) R.string.touch_probe_ready else R.string.touch_probe_idle)
            else snapshot.runtimeStatus?.code ?: unknown)
        source.text = context.getString(when (snapshot.lastCacheHit) {
            true -> R.string.touch_probe_source_cache
            false -> R.string.touch_probe_source_native
            null -> R.string.touch_probe_source_unknown
        })
        lastSuccess.text = snapshot.lastSuccessMonotonicNanos?.let {
            context.getString(R.string.touch_probe_last_native_success,
                ((System.nanoTime() - it).coerceAtLeast(0) / 1_000_000_000L))
        } ?: context.getString(R.string.touch_probe_no_native_success)
        failure.text = snapshot.lastFailure?.let {
            context.getString(R.string.touch_probe_failure_reason, it)
        }.orEmpty()
        failure.visibility = if (snapshot.lastFailure == null) View.GONE else View.VISIBLE
        fun ms(value: Long?) = value?.let { String.format(Locale.getDefault(), "%.2f", it / 1_000_000.0) } ?: "—"
        timing.text = context.getString(R.string.touch_probe_timing,
            ms(snapshot.lastQueryElapsedNanos), ms(snapshot.lastLibraryLoadNanos),
            ms(snapshot.lastInitializationNanos), ms(snapshot.lastNativeQueryNanos))
        counts.text = context.getString(R.string.touch_probe_query_counts,
            snapshot.queryCount, snapshot.successCount, snapshot.noCandidateCount,
            snapshot.timeoutCount, snapshot.unavailableCount, snapshot.cacheHitCount,
            snapshot.nativeQueryCount)
    }
}
