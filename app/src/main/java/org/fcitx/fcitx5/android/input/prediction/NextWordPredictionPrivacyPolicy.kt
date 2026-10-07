/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags

/** Ordinary Chinese text only. No surrounding-text reads or personalized learning are needed. */
internal object NextWordPredictionPrivacyPolicy {
    fun allows(editor: EditorInfo?, capabilities: CapabilityFlags? = null): Boolean = editor != null &&
        allows(editor.inputType, editor.imeOptions, capabilities)

    fun allows(inputType: Int, imeOptions: Int, capabilities: CapabilityFlags? = null): Boolean {
        if (capabilities?.has(CapabilityFlag.Password) == true ||
            capabilities?.has(CapabilityFlag.Sensitive) == true) return false
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (imeOptions and (EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_FORCE_ASCII) != 0) return false
        if (inputType and (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI -> false
            else -> true
        }
    }
}
