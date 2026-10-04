/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

/** Remembers closed offers across input-view recreation without retaining copied text. */
internal class ClipboardSuggestionDismissals {
    enum class Source { Clipboard, Sms }

    data class Token(val source: Source, val timestamp: Long, val entryId: Int = 0)

    private val dismissed = mutableMapOf<Source, Token>()

    fun isDismissed(token: Token): Boolean = dismissed[token.source] == token

    fun dismiss(token: Token) {
        dismissed[token.source] = token
    }

    companion object {
        // The clipboard manager and verification-code cache also live for this process.
        val shared = ClipboardSuggestionDismissals()
    }
}
