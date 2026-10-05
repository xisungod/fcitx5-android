/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import kotlin.math.roundToInt

/** Keeps AndroidX's persistence, dialogs and accessibility while presenting calm grouped cards. */
class AXiangPreferenceGroupAdapter(group: PreferenceGroup) : PreferenceGroupAdapter(group) {
    private val palette = AXiangSettingsPalette.from(group.context)
    private val density = group.context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    private fun sharesCard(a: Preference?, b: Preference?): Boolean =
        a != null && b != null && a !is PreferenceCategory && b !is PreferenceCategory && a.parent === b.parent

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position) ?: return
        val category = preference is PreferenceCategory
        val view = holder.itemView
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        ViewCompat.setAccessibilityHeading(view, category)
        if (category) {
            view.background = ColorDrawable(Color.TRANSPARENT)
            // Keep category headers legible even though PreferenceCategory itself is non-selectable.
            holder.findViewById(android.R.id.title)?.let { title ->
                (title as? TextView)?.apply {
                    setTextColor(palette.secondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            }
        } else {
            val first = !sharesCard(getItem(position - 1), preference)
            val last = !sharesCard(preference, getItem(position + 1))
            val top = if (first) dp(18).toFloat() else 0f
            val bottom = if (last) dp(18).toFloat() else 0f
            val corners = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            val surface = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadii = corners
            }
            view.background = if (preference.isSelectable) {
                val mask = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadii = corners
                }
                RippleDrawable(ColorStateList.valueOf(palette.ripple), surface, mask)
            } else surface
            view.minimumHeight = dp(64)
            (holder.findViewById(android.R.id.title) as? TextView)?.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(context.getColorStateList(R.color.ax_settings_primary_text))
                isSingleLine = false
            }
            (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(context.getColorStateList(R.color.ax_settings_secondary_text))
                maxLines = Int.MAX_VALUE
            }
        }
        holder.findViewById(R.id.ax_settings_chevron)?.visibility =
            if (!category && preference.isSelectable && preference.widgetLayoutResource == 0)
                View.VISIBLE else View.GONE
    }

    /** Insets dividers within the white surface and separates unlabelled groups as well. */
    fun decoration() = object : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.divider
            strokeWidth = density.coerceAtLeast(1f)
        }

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION || !sharesCard(getItem(position), getItem(position + 1))) continue
                val y = child.bottom + child.translationY - paint.strokeWidth / 2f
                canvas.drawLine(child.left + dp(18).toFloat(), y, child.right - dp(18).toFloat(), y, paint)
            }
        }

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val current = getItem(position)
            val next = getItem(position + 1)
            if (current !is PreferenceCategory && next != null && next !is PreferenceCategory && !sharesCard(current, next)) {
                outRect.bottom = dp(12)
            }
        }
    }
}
