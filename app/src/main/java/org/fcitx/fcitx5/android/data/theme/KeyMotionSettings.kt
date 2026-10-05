/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

/** Percentages describe keycap scale; rebound duration includes the rise and the soft landing. */
internal data class KeyMotionSettings(
    val pressAmplitude: Int = 8,
    val pressDuration: Int = 180,
    val reboundAmplitude: Int = 3,
    val reboundDuration: Int = 1000
) {
    fun normalized() = copy(
        pressAmplitude = pressAmplitude.coerceIn(PRESS_AMPLITUDE_RANGE),
        pressDuration = pressDuration.coerceIn(PRESS_DURATION_RANGE),
        reboundAmplitude = reboundAmplitude.coerceIn(REBOUND_AMPLITUDE_RANGE),
        reboundDuration = reboundDuration.coerceIn(REBOUND_DURATION_RANGE)
    )

    companion object {
        val PRESS_AMPLITUDE_RANGE = 2..16
        val PRESS_DURATION_RANGE = 60..300
        val REBOUND_AMPLITUDE_RANGE = 1..6
        val REBOUND_DURATION_RANGE = 400..1800

        fun from(prefs: ThemePrefs) = KeyMotionSettings(
            prefs.pressMotionAmplitude.getValue(), prefs.pressMotionDuration.getValue(),
            prefs.reboundMotionAmplitude.getValue(), prefs.reboundMotionDuration.getValue()
        ).normalized()
    }
}
