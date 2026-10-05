/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager

/** Relative widths, kept separately for each layout. Defaults occupy no storage. */
internal data class KeyWidthSettings(val overrides: Map<String, Int> = emptyMap()) {
    operator fun get(id: String): Int = overrides[id]?.coerceIn(RANGE) ?: 100

    fun withWidth(id: String, percent: Int): KeyWidthSettings {
        val next = overrides.toMutableMap()
        if (percent.coerceIn(RANGE) == 100) next.remove(id) else next[id] = percent.coerceIn(RANGE)
        return KeyWidthSettings(next)
    }

    fun reset(profile: KeyWidthProfile) = KeyWidthSettings(overrides.filterKeys { !it.startsWith("${profile.name}:") })

    fun encode(): String = overrides.toSortedMap().entries.filter { it.value != 100 }
        .joinToString(";") { "${it.key}=${it.value.coerceIn(RANGE)}" }

    companion object {
        val RANGE = 70..150
        fun parse(raw: String): KeyWidthSettings = KeyWidthSettings(raw.split(';').take(512).mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size != 2 || !parts[0].matches(Regex("[A-Za-z0-9:_-]{1,96}"))) null
            else parts[1].toIntOrNull()?.coerceIn(RANGE)?.takeIf { value -> value != 100 }?.let { value -> parts[0] to value }
        }.toMap())

        /** Proportional reflow with a floor, so neighbours cannot collapse to tiny hit targets. */
        fun distribute(base: List<Float>, factors: List<Int>, span: Float = base.sum()): List<Float> {
            require(base.size == factors.size)
            if (base.isEmpty()) return emptyList()
            val baseTotal = base.sum()
            require(baseTotal > 0f && span > 0f)
            val weights = base.indices.map { base[it] * factors[it].coerceIn(RANGE) / 100f }
            val minimum = base.map { it / baseTotal * span * 0.65f }
            val out = MutableList(base.size) { 0f }
            val remaining = base.indices.toMutableSet()
            var available = span
            while (remaining.isNotEmpty()) {
                val total = remaining.sumOf { weights[it].toDouble() }.toFloat()
                val small = remaining.filter { available * weights[it] / total < minimum[it] }
                if (small.isEmpty()) {
                    remaining.forEach { out[it] = available * weights[it] / total }
                    break
                }
                small.forEach { out[it] = minimum[it]; available -= minimum[it]; remaining.remove(it) }
            }
            // Derive the last boundary from the row span, avoiding accumulated rounding gaps.
            out[out.lastIndex] += span - out.sum()
            return out
        }
    }
}

internal enum class KeyWidthProfile(val title: Int) {
    Text(R.string.key_width_layout_26), T9(R.string.key_width_layout_t9),
    Number(R.string.key_width_layout_number), Symbols(R.string.key_width_layout_symbols)
}

internal object KeyWidthGeometry {
    fun textId(def: KeyDef.Appearance): String = "Text:" + when (def.viewId) {
        R.id.button_space -> "space"
        R.id.button_return -> "return"
        R.id.button_backspace -> "backspace"
        R.id.button_caps -> "caps"
        R.id.button_lang -> "language"
        else -> (def as? KeyDef.Appearance.Text)?.displayText.orEmpty()
            .map { it.code.toString(16) }.joinToString("_")
    }

    fun gridId(profile: KeyWidthProfile, index: Int): String {
        val railCount = if (profile == KeyWidthProfile.Symbols) 5 else 4
        return "${profile.name}:" + if (index < railCount) "rail" else index.toString()
    }

    fun grid(profile: KeyWidthProfile, original: List<KeyboardCell>, settings: KeyWidthSettings): List<KeyboardCell> {
        if (settings.overrides.keys.none { it.startsWith("${profile.name}:") }) return original
        val railCount = if (profile == KeyWidthProfile.Symbols) 5 else 4
        val originalRail = original.first().width
        val ratio = settings["${profile.name}:rail"] / 100f
        val railWidth = originalRail * ratio / (originalRail * ratio + 1f - originalRail)
        val result = original.mapIndexed { index, cell ->
            if (index < railCount) cell.copy(width = railWidth) else cell
        }.toMutableList()
        original.indices.drop(railCount).groupBy { original[it].top to original[it].height }.values.forEach { group ->
            val sorted = group.sortedBy { original[it].left }
            val besideRail = original[sorted.first()].left > 0f
            val start = if (besideRail) railWidth else 0f
            val widths = KeyWidthSettings.distribute(sorted.map { original[it].width },
                sorted.map { settings[gridId(profile, it)] }, 1f - start)
            var left = start
            sorted.forEachIndexed { position, index ->
                result[index] = original[index].copy(left = left, width = widths[position])
                left += widths[position]
            }
        }
        return result
    }
}

/** Changes real KeyView bounds: the existing hit tester, popup anchors and effects follow them. */
internal fun TextKeyboard.applyKeyWidths() {
    val settings = KeyWidthSettings.parse(ThemeManager.prefs.keyWidthOverrides.getValue())
    children.filterIsInstance<ConstraintLayout>().forEach { row ->
        val all = row.children.filterIsInstance<KeyView>().toList()
        val visible = all.filter { it.visibility != View.GONE }
        if (visible.isEmpty()) return@forEach
        val hiddenWidth = all.filter { it.visibility == View.GONE }.sumOf { it.def.percentWidth.toDouble() }.toFloat()
        val base = visible.map { it.def.percentWidth + if (it.id == R.id.button_return) hiddenWidth else 0f }
        val span = base.sum().coerceAtMost(1f)
        val widths = KeyWidthSettings.distribute(base, visible.map { settings[KeyWidthGeometry.textId(it.def)] }, span)
        val free = if (AppPrefs.getInstance().keyboard.expandKeypressArea.getValue()) (1f - span) / 2f else 0f
        visible.forEachIndexed { index, key ->
            val extraLeft = if (index == 0) free else 0f
            val extraRight = if (index == visible.lastIndex) free else 0f
            key.layoutMarginLeft = extraLeft / (widths[index] + extraLeft)
            key.layoutMarginRight = extraRight / (widths[index] + extraRight)
            key.updateLayoutParams<ConstraintLayout.LayoutParams> {
                matchConstraintPercentWidth = widths[index] + extraLeft + extraRight
            }
        }
    }
}
