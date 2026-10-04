package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.data.BuiltinRimeProfile
import org.junit.Assert.*
import org.junit.Test

class BuiltinRimeProfileTest {
    private val stock = """
        # Current user's default group
        [Groups/0]
        Name=默认
        Default Layout=us
        DefaultIM=pinyin

        [Groups/0/Items/0]
        Name=keyboard-us
        Layout=

        [Groups/0/Items/1]
        Name=pinyin
        Layout=

        [GroupOrder]
        0=默认
    """.trimIndent() + "\n"

    @Test fun freshInstallEnablesRimeWithEnglishAndActiveChineseForEveryLocale() {
        val update = BuiltinRimeProfile.plan(null, null, false)!!
        assertTrue(update.profile.contains("DefaultIM=rime"))
        assertTrue(update.profile.contains("Name=keyboard-us"))
        assertTrue(update.profile.contains("[Groups/0/Items/1]\nName=rime"))
        assertFalse(update.profile.contains("pinyin"))
        assertTrue(update.config.contains("[Behavior]\nActiveByDefault=True"))
    }

    @Test fun upgradeMigratesOnlyStockPinyinOnceAndKeepsOtherConfig() {
        val config = "# keep\n[Behavior]\nActiveByDefault=False\nShareInputState=All\n\n[Hotkey]\nFoo=Bar\n"
        val update = BuiltinRimeProfile.plan(stock, config, false)!!
        assertEquals(stock.replace("=pinyin", "=rime"), update.profile)
        assertEquals(config.replace("ActiveByDefault=False", "ActiveByDefault=True"), update.config)
        // A subsequent user choice to go back to Pinyin must survive restarts.
        assertNull(BuiltinRimeProfile.plan(stock, config, true))
        assertNull(BuiltinRimeProfile.plan(update.profile, update.config, true))
    }

    @Test fun existingCustomListsLayoutsGroupsAndMalformedProfilesAreUntouched() {
        val variations = listOf(
            stock.replace("Name=pinyin", "Name=shuangpin"),
            stock.replace("DefaultIM=pinyin", "DefaultIM=keyboard-us"),
            stock.replace("Default Layout=us", "Default Layout=de"),
            stock.replace("Layout=\n", "Layout=de\n"),
            stock + "\n[Groups/0/Items/2]\nName=rime\nLayout=\n",
            stock + "\n[Groups/1]\nName=Custom\nDefault Layout=us\nDefaultIM=rime\n",
            stock + "\n[Groups/0]\nDefaultIM=pinyin\n",
            stock.replace("DefaultIM=pinyin", "DefaultIM=pinyin\nDefaultIM=rime"),
            "", "broken profile"
        )
        variations.forEach { assertNull(it, BuiltinRimeProfile.plan(it, null, false)) }
    }

    @Test fun missingBehaviorSectionIsAddedWithoutDroppingExistingGlobalSettings() {
        val update = BuiltinRimeProfile.plan(null, "[Hotkey]\nFoo=Bar\n", false)!!
        assertTrue(update.config.startsWith("[Hotkey]\nFoo=Bar\n"))
        assertTrue(update.config.contains("[Behavior]\nActiveByDefault=True"))
    }
}
