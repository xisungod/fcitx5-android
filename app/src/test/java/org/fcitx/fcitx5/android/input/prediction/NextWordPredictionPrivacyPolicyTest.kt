/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.junit.Assert.*
import org.junit.Test

class NextWordPredictionPrivacyPolicyTest {
    @Test fun unknownNumericPhoneAndDateEditorsAreExcluded() {
        assertFalse(NextWordPredictionPrivacyPolicy.allows(null))
        listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME, 15).forEach {
            assertFalse(NextWordPredictionPrivacyPolicy.allows(it, 0))
        }
    }

    @Test fun allPasswordEmailAndUriVariationsAreExcluded() {
        listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI).forEach {
            assertFalse(NextWordPredictionPrivacyPolicy.allows(InputType.TYPE_CLASS_TEXT or it, 0))
        }
    }

    @Test fun privateAsciiAndEditorsOwnCompletionOverrideTextEligibility() {
        listOf(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, EditorInfo.IME_FLAG_FORCE_ASCII).forEach {
            assertFalse(NextWordPredictionPrivacyPolicy.allows(InputType.TYPE_CLASS_TEXT,
                it or EditorInfo.IME_ACTION_DONE))
        }
        listOf(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE).forEach {
            assertFalse(NextWordPredictionPrivacyPolicy.allows(InputType.TYPE_CLASS_TEXT or it, 0))
        }
    }

    @Test fun eitherSensitiveCapabilityAloneIsSufficientToExcludeTheEditor() {
        listOf(CapabilityFlag.Password, CapabilityFlag.Sensitive).forEach {
            assertFalse(NextWordPredictionPrivacyPolicy.allows(InputType.TYPE_CLASS_TEXT, 0,
                CapabilityFlags(it)))
        }
    }

    @Test fun ordinaryMultilineAndWebTextCanUseReadOnlyPrediction() {
        listOf(InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT).forEach {
            assertTrue(NextWordPredictionPrivacyPolicy.allows(it, EditorInfo.IME_ACTION_DONE,
                CapabilityFlags.DefaultFlags))
        }
    }
}
