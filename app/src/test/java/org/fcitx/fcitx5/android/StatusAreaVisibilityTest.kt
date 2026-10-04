/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.input.status.compactStatusActions
import org.junit.Assert.*
import org.junit.Test

class StatusAreaVisibilityTest {
    private fun action(id: Int, name: String, text: String, children: Array<Action>? = null) =
        Action(id, false, false, false, name, "", text, text, children)

    @Test fun quickToolsHideRimeMaintenanceAndDecorativeOptionsWithoutDamagingSchemaActions() {
        val pinyin = action(91, "", "雾凇拼音")
        val nineKey = action(92, "", "雾凇九键")
        val schemas = action(4, "fcitx-rime-im", "雾凇拼音", arrayOf(pinyin, nineKey))
        val traditional = action(8, "fcitx-rime-rime_ice-traditionalization", "简")
        val fullWidth = action(9, "fcitx-rime-rime_ice-full_shape", "半角")
        val spelling = action(10, "spell", "Spelling")
        val original = arrayOf(
            schemas,
            action(5, "fcitx-rime-rime_ice-ascii_punct", "$"),
            action(6, "fcitx-rime-rime_ice-emoji", "😄"),
            action(7, "fcitx-rime-deploy", "重新部署"),
            traditional, fullWidth, spelling,
            action(11, "fcitx-rime-rime_ice-search_single_char", "单字")
        )
        val snapshot = original.copyOf()
        val visible = compactStatusActions(original)
        assertEquals(listOf(traditional, fullWidth, spelling), visible)
        assertArrayEquals(snapshot, original)
        assertSame(pinyin, RimeActions.pinyinSchema(original, false))
        assertSame(nineKey, RimeActions.pinyinSchema(original, true))
    }
}
