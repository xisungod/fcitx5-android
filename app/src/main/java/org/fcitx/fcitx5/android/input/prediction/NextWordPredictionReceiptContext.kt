/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/** The coroutine element restores the previous receipt at every suspension and exit. */
internal object NextWordPredictionReceiptContext {
    private val origin = ThreadLocal<NextWordPredictionOrigin?>()

    fun currentOrigin(): NextWordPredictionOrigin? = origin.get()

    fun element(value: NextWordPredictionOrigin?): ThreadContextElement<NextWordPredictionOrigin?> =
        origin.asContextElement(value)
}
