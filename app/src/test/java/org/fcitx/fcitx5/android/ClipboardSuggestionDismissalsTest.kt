/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.bar.ClipboardSuggestionDismissals
import org.fcitx.fcitx5.android.input.bar.ClipboardSuggestionDismissals.Source
import org.fcitx.fcitx5.android.input.bar.ClipboardSuggestionDismissals.Token
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSuggestionDismissalsTest {
    @Test
    fun replayingTheClosedCopyIsSuppressedButCopyingTheSameHistoryEntryAgainIsAllowed() {
        val dismissals = ClipboardSuggestionDismissals()
        val copied = Token(Source.Clipboard, timestamp = 1_000, entryId = 42)
        assertFalse(dismissals.isDismissed(copied))
        dismissals.dismiss(copied)
        // Rebuilding the input view receives a new entry object with the same copy identity.
        assertTrue(dismissals.isDismissed(copied.copy()))
        assertFalse(dismissals.isDismissed(copied.copy(timestamp = 2_000)))
        assertFalse(dismissals.isDismissed(copied.copy(entryId = 43)))
    }

    @Test
    fun closingASmsOfferDoesNotSuppressTheClipboardOrTheNextSms() {
        val dismissals = ClipboardSuggestionDismissals()
        val copied = Token(Source.Clipboard, timestamp = 1_000)
        val sms = Token(Source.Sms, timestamp = 1_000)
        dismissals.dismiss(sms)
        assertTrue(dismissals.isDismissed(sms))
        assertFalse(dismissals.isDismissed(copied))
        assertFalse(dismissals.isDismissed(sms.copy(timestamp = 2_000)))
        dismissals.dismiss(copied)
        assertTrue(dismissals.isDismissed(copied))
        assertTrue("Each source keeps its own closed offer across view recreation",
            dismissals.isDismissed(sms))
    }
}
