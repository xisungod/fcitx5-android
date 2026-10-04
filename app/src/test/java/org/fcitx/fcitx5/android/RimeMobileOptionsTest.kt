package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.data.rime.RimeFuzzyConfig
import org.fcitx.fcitx5.android.input.keyboard.PunctuationMode
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.junit.Assert.*
import org.junit.Test

class RimeMobileOptionsTest {
    private fun action(id: Int, name: String, text: String, children: Array<Action>? = null) =
        Action(id, false, false, false, name, "", text, text, children)
    @Test fun nativeRimeToggleIsFoundWithoutDependingOnLocalizedTextOrSchemaId() {
        val toggle = action(71, "", "拉丁模式")
        val deploy = action(93, "fcitx-rime-deploy", "部署")
        val root = action(5, "fcitx-rime-im", "雾凇", arrayOf(toggle, action(72, "", "雾凇拼音"), deploy))
        assertEquals(toggle, RimeActions.asciiToggle(arrayOf(root)))
        assertEquals(deploy, RimeActions.find(arrayOf(root), "fcitx-rime-deploy"))
        assertNull(RimeActions.asciiToggle(arrayOf(action(9, "pinyin", "拼音"))))
    }
    @Test fun fuzzyGroupsDefaultOffAndCanBeEnabledIndependently() {
        assertTrue(RimeFuzzyConfig.render(false, false, false).contains("patch: {}"))
        val nl = RimeFuzzyConfig.render(true, false, false)
        assertTrue(nl.contains("derive/^n/l/"))
        assertFalse(nl.contains("derive/ang$/an/"))
        val rest = RimeFuzzyConfig.render(false, true, true)
        assertFalse(rest.contains("derive/^n/l/"))
        assertTrue(rest.contains("derive/^([zcs])h/$1/"))
        assertTrue(rest.contains("derive/ang$/an/"))
    }
    @Test fun longPressPunctuationUsesEnglishAsciiOrChineseFullWidth() {
        assertEquals(listOf(",", ".", "?", "!", "..."), listOf("，", "。", "？", "！", "…").map { PunctuationMode.forMode(it, true) })
        assertEquals("，", PunctuationMode.forMode("，", false))
    }
}
