/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.fcitx.fcitx5.android.core.FcitxAPI

/**
 * Run inside the input transaction at the selected action's original queue position.
 * Later queued letters cannot revoke that position. Context/config cancellation
 * remains the caller's [allowed] gate; native composition must match exactly.
 */
internal suspend fun FcitxAPI.resolveReservedPinyinTouchCandidate(
    reservation: PinyinTouchCandidateRuntime.Reserved,
    editor: Any?,
    allowed: () -> Boolean
): Boolean {
    val preedit = inputPanelCached.preedit
    if (!allowed() || reservation.editorIdentity !== editor ||
        preedit.toString() != reservation.expectedPreedit ||
        preedit.cursor != reservation.expectedCursor) return false
    return resolvePinyinTouchCandidate(reservation.offer, allowed)
}
