/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

/** Selects pending ordinary taps by their DOWN sequence when a later tap is released. */
internal object TapDownOrder {
    data class Contact(val pointerId: Int, val downSequence: Long, val eligible: Boolean)

    fun olderPointersToRelease(releasingPointerId: Int, contacts: List<Contact>): List<Int> {
        val releasing = contacts.firstOrNull { it.pointerId == releasingPointerId }
            ?.takeIf { it.eligible } ?: return emptyList()
        return contacts.asSequence()
            .filter { it.eligible && it.downSequence < releasing.downSequence }
            .sortedBy { it.downSequence }
            .map { it.pointerId }
            .toList()
    }
}
