/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.neural

import androidx.annotation.Keep

@Keep
internal object MiniRbtNative {
    init { System.loadLibrary("axiang-neural") }

    @JvmStatic external fun create(modelPath: String): Long
    @JvmStatic external fun score(
        session: Long, inputIds: LongArray, attentionMask: LongArray, tokenTypes: LongArray,
        maskedPositions: LongArray, targetIds: LongArray, batch: Int, length: Int
    ): FloatArray
    @JvmStatic external fun destroy(session: Long)
}
