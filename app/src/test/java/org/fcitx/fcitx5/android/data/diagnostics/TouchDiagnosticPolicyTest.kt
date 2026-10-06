/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.diagnostics

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class TouchDiagnosticPolicyTest {
    @Test fun unknownEditorsAndNonTextFieldsFailClosed() {
        assertFalse(TouchDiagnosticPolicy.allows(null))
        listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME, 15).forEach {
            assertFalse(TouchDiagnosticPolicy.allows(it, 0))
        }
    }

    @Test fun allPasswordEmailAndUriVariationsAreExcluded() {
        listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI).forEach {
            assertFalse(TouchDiagnosticPolicy.allows(InputType.TYPE_CLASS_TEXT or it, 0))
        }
    }

    @Test fun privateLearningFlagOverridesOrdinaryText() {
        assertFalse(TouchDiagnosticPolicy.allows(InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE))
    }

    @Test fun ordinaryMultilineAndWebTextRemainAvailable() {
        listOf(InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT).forEach {
            assertTrue(TouchDiagnosticPolicy.allows(it, EditorInfo.IME_ACTION_DONE))
        }
    }
}
