/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.junit.Assert.*
import org.junit.Test

class RimeActionsTest {
    private fun action(id: Int, text: String, name: String = "", separator: Boolean = false,
                       children: Array<Action>? = null) =
        Action(id, separator, false, false, name, "", text, text, children)

    private fun schemaMenu(vararg actions: Action) =
        action(8, "方案", name = "fcitx-rime-im", children = arrayOf(*actions))

    @Test
    fun schemaSelectionUsesTheCurrentDynamicActionIdWithoutDependingOnOrder() {
        for ((fullId, t9Id) in listOf(71 to 93, 1_024 to 5_019)) {
            val full = action(fullId, RimeActions.FULL_PINYIN_SCHEMA)
            val t9 = action(t9Id, RimeActions.NINE_KEY_SCHEMA)
            val actions = arrayOf(schemaMenu(action(3, "Latin mode"), t9, full))
            assertSame(t9, RimeActions.pinyinSchema(actions, nineKey = true))
            assertSame(full, RimeActions.pinyinSchema(actions, nineKey = false))
        }
    }

    @Test
    fun matchingTextOutsideTheRimeSchemaMenuCannotBeMistakenForASelectableSchema() {
        val outside = action(4, RimeActions.NINE_KEY_SCHEMA)
        val nested = action(5, "Other engine", children = arrayOf(outside))
        val full = action(71, RimeActions.FULL_PINYIN_SCHEMA)
        val actions = arrayOf(outside, nested, schemaMenu(action(3, "拉丁模式"), full))
        assertNull(RimeActions.pinyinSchema(actions, nineKey = true))
        assertSame(full, RimeActions.pinyinSchema(actions, nineKey = false))
        assertNull(RimeActions.pinyinSchema(arrayOf(outside, nested), nineKey = true))
    }

    @Test
    fun schemaMenuMayBeNestedButSeparatorsAliasesAndNestedActionLabelsAreNotSchemas() {
        val full = action(71, RimeActions.FULL_PINYIN_SCHEMA)
        val invalid = arrayOf(
            action(2, RimeActions.NINE_KEY_SCHEMA, separator = true),
            action(3, "雾凇九键（自定义）"),
            action(4, "Submenu", children = arrayOf(action(5, RimeActions.NINE_KEY_SCHEMA)))
        )
        val wrapped = action(9, "Rime tools", children = arrayOf(schemaMenu(*invalid, full)))
        assertNull(RimeActions.pinyinSchema(arrayOf(wrapped), nineKey = true))
        assertSame(full, RimeActions.pinyinSchema(arrayOf(wrapped), nineKey = false))
    }

    @Test
    fun asciiToggleUsesTheNativeFirstActionAndRejectsMissingOrSeparatorEntries() {
        val latin = action(503, "Localized Latin mode")
        assertSame(latin, RimeActions.asciiToggle(arrayOf(schemaMenu(latin,
            action(71, RimeActions.FULL_PINYIN_SCHEMA)))))
        assertNull(RimeActions.asciiToggle(arrayOf(schemaMenu())))
        assertNull(RimeActions.asciiToggle(arrayOf(schemaMenu(action(1, "", separator = true), latin))))
    }

    @Test
    fun activeSchemaVerificationRejectsOtherEnginesAndAsciiEvenWhenTheNameMatches() {
        val ime = InputMethodEntry("rime", "Rime", "", "Rime", "中", "zh_CN", "rime", true,
            InputMethodSubMode(RimeActions.NINE_KEY_SCHEMA, "中", "fcitx_rime"))
        assertTrue(RimeActions.isPinyinSchema(ime, nineKey = true))
        assertFalse(RimeActions.isPinyinSchema(ime, nineKey = false))
        assertFalse(RimeActions.isPinyinSchema(ime.copy(uniqueName = "pinyin"), nineKey = true))
        assertFalse(RimeActions.isPinyinSchema(ime.copy(subMode = ime.subMode.copy(label = "A")), nineKey = true))
        assertTrue(RimeActions.isPinyinSchema(
            ime.copy(subMode = ime.subMode.copy(name = RimeActions.FULL_PINYIN_SCHEMA)), nineKey = false))
    }
}
