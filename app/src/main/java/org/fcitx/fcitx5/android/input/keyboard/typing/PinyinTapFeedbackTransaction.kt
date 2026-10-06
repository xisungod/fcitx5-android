/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym

/** Guard and replacement share the native queue with candidate selection, not just the IME job queue. */
internal suspend fun FcitxAPI.resolvePinyinTapFeedback(
    runtime: PinyinTapRuntime,
    token: Long,
    editorIdentity: Any?,
    restore: Boolean,
    allowed: FcitxAPI.() -> Boolean
): PinyinTapRuntime.Pending? = withInputTransaction {
    if (!allowed()) {
        runtime.nextAction()
        return@withInputTransaction null
    }
    val preedit = inputPanelCached.preedit
    val spelling = PinyinTapRuntime.spellingAtEnd(preedit.toString(), preedit.cursor)
    val pending = runtime.takePending(token, editorIdentity, spelling)
        ?: return@withInputTransaction null
    if (restore) {
        // Nested sendKey runs inline on this native dispatcher, with no suspension between the keys.
        sendKey(KeySym(FcitxKeyMapping.FcitxKey_BackSpace), KeyStates.Virtual)
        sendKey(pending.feedback.original, pending.states, pending.originalCode)
    }
    pending
}
