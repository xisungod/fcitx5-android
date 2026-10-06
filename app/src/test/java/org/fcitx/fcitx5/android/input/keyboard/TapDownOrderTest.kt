/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDownOrderTest {
    @Test fun newestOfThreeReleasesBothOlderContactsInDownOrder() {
        val contacts = listOf(
            TapDownOrder.Contact(7, 1L, true),
            TapDownOrder.Contact(23, 2L, true),
            TapDownOrder.Contact(4, 3L, true)
        )
        assertEquals(listOf(7, 23), TapDownOrder.olderPointersToRelease(4, contacts))
        assertEquals(listOf(7), TapDownOrder.olderPointersToRelease(23, contacts))
        assertTrue(TapDownOrder.olderPointersToRelease(7, contacts).isEmpty())
    }

    @Test fun pointerIdsAndInputListOrderDoNotDetermineDownOrder() {
        val contacts = listOf(
            TapDownOrder.Contact(3, 20L, true),
            TapDownOrder.Contact(9, 30L, true),
            TapDownOrder.Contact(42, 10L, true),
            TapDownOrder.Contact(1, 40L, true)
        )
        assertEquals(listOf(42, 3), TapDownOrder.olderPointersToRelease(9, contacts))
    }

    @Test fun anIneligibleReleaseSuchAsAModifierDoesNotReleaseOrdinaryLetters() {
        val contacts = listOf(
            TapDownOrder.Contact(7, 1L, true),
            TapDownOrder.Contact(23, 2L, false)
        )
        assertTrue(TapDownOrder.olderPointersToRelease(23, contacts).isEmpty())
    }

    @Test fun consumedAndOtherIneligibleOlderContactsAreExcluded() {
        val contacts = listOf(
            TapDownOrder.Contact(7, 1L, false),
            TapDownOrder.Contact(23, 2L, true),
            TapDownOrder.Contact(4, 3L, false),
            TapDownOrder.Contact(11, 4L, true)
        )
        assertEquals(listOf(23), TapDownOrder.olderPointersToRelease(11, contacts))
    }

    @Test fun anUnknownReleasedPointerDoesNotReleasePendingContacts() {
        assertTrue(TapDownOrder.olderPointersToRelease(99,
            listOf(TapDownOrder.Contact(7, 1L, true))).isEmpty())
        assertTrue(TapDownOrder.olderPointersToRelease(99, emptyList()).isEmpty())
    }

    @Test fun equalDownTimestampsRemainOrderedByDistinctDownSequences() {
        // MotionEvents can share an eventTime; the caller assigns each DOWN a sequence.
        val contacts = listOf(
            TapDownOrder.Contact(23, 102L, true),
            TapDownOrder.Contact(7, 101L, true)
        )
        assertEquals(listOf(7), TapDownOrder.olderPointersToRelease(23, contacts))
    }

    @Test fun contactsWithTheSameSequenceAreNotStrictlyOlder() {
        val contacts = listOf(
            TapDownOrder.Contact(7, 1L, true),
            TapDownOrder.Contact(23, 2L, true),
            TapDownOrder.Contact(4, 2L, true)
        )
        assertEquals(listOf(7), TapDownOrder.olderPointersToRelease(4, contacts))
    }
}
