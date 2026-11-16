/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** The common fade curve; each halo passes the time since the wave reached its key. */
internal fun samLightFade(ageMs: Float, holdMs: Long, fadeMs: Long): Float {
    val progress = ((ageMs - holdMs) / fadeMs).coerceIn(0f, 1f)
    return 1f - progress * progress * (3f - 2f * progress)
}

/** At 60% of the fade the easing curve still has 35.2% of its initial brightness. */
internal fun samHeldFaceFadeAge(holdMs: Long, fadeMs: Long): Float = holdMs + fadeMs * 0.6f
