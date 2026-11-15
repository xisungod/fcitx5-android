/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** The pressed key and its source halo fade on the same clock. Neighbours pass their local age. */
internal fun samLightFade(ageMs: Float, holdMs: Long, fadeMs: Long): Float {
    val progress = ((ageMs - holdMs) / fadeMs).coerceIn(0f, 1f)
    return 1f - progress * progress * (3f - 2f * progress)
}
