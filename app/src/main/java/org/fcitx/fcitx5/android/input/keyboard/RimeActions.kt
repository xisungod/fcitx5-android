/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.InputMethodEntry

internal object RimeActions {
    const val FULL_PINYIN_SCHEMA = "雾凇拼音"
    const val NINE_KEY_SCHEMA = "雾凇九键"

    // These schema names belong to our pinned bundled data, not translated UI strings.
    // Native schema actions have dynamic IDs and no stable name in this Fcitx version.
    fun pinyinSchema(actions: Array<Action>, nineKey: Boolean): Action? {
        val name = if (nineKey) NINE_KEY_SCHEMA else FULL_PINYIN_SCHEMA
        return find(actions, "fcitx-rime-im")?.menu?.firstOrNull {
            !it.isSeparator && it.shortText == name
        }
    }

    fun isPinyinSchema(ime: InputMethodEntry, nineKey: Boolean): Boolean =
        ime.uniqueName == "rime" && !TextKeyboard.isEnglish(ime) &&
            ime.subMode.name == (if (nineKey) NINE_KEY_SCHEMA else FULL_PINYIN_SCHEMA)

    fun find(actions: Array<Action>, name: String): Action? {
        for (action in actions) {
            if (action.name == name) return action
            action.menu?.let { find(it, name)?.let { found -> return found } }
        }
        return null
    }
    // In the pinned fcitx5-rime, updateSchemaMenu always puts the native Latin
    // toggle first. Its numeric ID is dynamic; text is localized, so use neither.
    fun asciiToggle(actions: Array<Action>): Action? =
        find(actions, "fcitx-rime-im")?.menu?.firstOrNull()?.takeUnless { it.isSeparator }
}
