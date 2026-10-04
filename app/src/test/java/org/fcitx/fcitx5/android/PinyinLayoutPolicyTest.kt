/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.text.InputType
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.input.keyboard.PinyinLayoutPolicy
import org.fcitx.fcitx5.android.input.keyboard.PinyinT9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.Assert.*
import org.junit.Test

class PinyinLayoutPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private fun rime(schema: String = RimeActions.NINE_KEY_SCHEMA) = InputMethodEntry(
        "rime", "Rime", "", "Rime", "中", "zh_CN", "rime", true,
        InputMethodSubMode(schema, "中", "fcitx_rime"))

    @Test
    fun theActualSchemaSelectsT9AndSwitchingBackOrUsingAnotherEngineRestores26Keys() {
        val nineKey = rime()
        assertEquals(PinyinT9Keyboard.Name, PinyinLayoutPolicy.textLayout(nineKey, text))
        assertEquals(TextKeyboard.Name, PinyinLayoutPolicy.textLayout(rime(RimeActions.FULL_PINYIN_SCHEMA), text))
        assertEquals(TextKeyboard.Name, PinyinLayoutPolicy.textLayout(rime("小鹤双拼"), text))
        assertEquals(TextKeyboard.Name, PinyinLayoutPolicy.textLayout(nineKey.copy(uniqueName = "pinyin"), text))
        assertEquals(TextKeyboard.Name, PinyinLayoutPolicy.textLayout(nineKey.copy(uniqueName = "keyboard-us"), text))
        // Returning from Symbols/Numbers resolves the current engine again, rather than a stale route.
        assertEquals(PinyinT9Keyboard.Name, PinyinLayoutPolicy.textLayout(nineKey, text))
    }

    @Test
    fun asciiIndicatorsOverrideTheT9SchemaName() {
        val nineKey = rime()
        for (english in listOf(
            nineKey.copy(subMode = nineKey.subMode.copy(icon = "fcitx_rime_latin")),
            nineKey.copy(subMode = nineKey.subMode.copy(icon = "fcitx_rime_latin_custom")),
            nineKey.copy(subMode = nineKey.subMode.copy(label = "A")),
            nineKey.copy(subMode = nineKey.subMode.copy(label = "a")),
            nineKey.copy(languageCode = "en_US")
        )) {
            assertEquals("ASCII must never type digit codes through a pinyin keypad: $english",
                TextKeyboard.Name, PinyinLayoutPolicy.textLayout(english, text))
        }
    }

    @Test
    fun passwordsEmailAndUrisNeverExposeThePinyinKeypad() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI)) {
            val inputType = text or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            assertFalse("Restricted variation $variation", PinyinLayoutPolicy.allowsPinyin(inputType))
            assertEquals(TextKeyboard.Name, PinyinLayoutPolicy.textLayout(rime(), inputType))
        }
    }

    @Test
    fun numericPasswordSignedDecimalAndPhoneFieldsKeepTheNumberRoute() {
        for (inputType in listOf(InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_PHONE)) {
            assertTrue(PinyinLayoutPolicy.isNumber(inputType))
            assertFalse(PinyinLayoutPolicy.allowsPinyin(inputType))
            assertEquals(TextKeyboard.Name, PinyinLayoutPolicy.textLayout(rime(), inputType))
        }
    }

    @Test
    fun ordinaryTextFlagsDoNotDisablePinyinAndTheExplicit26RouteRemainsDistinct() {
        val inputType = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        assertFalse(PinyinLayoutPolicy.isNumber(inputType))
        assertTrue(PinyinLayoutPolicy.allowsPinyin(inputType))
        assertEquals(PinyinT9Keyboard.Name, PinyinLayoutPolicy.textLayout(rime(), inputType))
        assertEquals(PinyinT9Keyboard.Text26Route, PinyinLayoutPolicy.FullRoute)
        assertNotEquals("Generic Text returns to the current schema; explicit 26 changes schema",
            TextKeyboard.Name, PinyinLayoutPolicy.FullRoute)
    }
}
