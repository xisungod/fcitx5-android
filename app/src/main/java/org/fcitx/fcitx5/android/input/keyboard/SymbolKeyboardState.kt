/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.SharedPreferences
import org.json.JSONArray

enum class SymbolCategory(val label: String) {
    Recent("最近"), Chinese("中文"), English("英文"), Emoticon("表情"), Internet("网络")
}

/** One shared history for all categories; strings are kept whole (including emoji and .com). */
class SymbolHistory(private val prefs: SharedPreferences) {
    companion object { private const val MaxItems = 64 }

    val items: List<String>
        get() = runCatching {
            val raw = JSONArray(prefs.getString("recent", "[]") ?: "[]")
            List(raw.length()) { raw.getString(it) }.filter { it.isNotBlank() }.distinct().take(MaxItems)
        }.getOrDefault(emptyList())

    fun record(symbol: String) {
        if (symbol.isBlank()) return
        val next = (listOf(symbol) + items).distinct().take(MaxItems)
        prefs.edit().putString("recent", JSONArray(next).toString()).apply()
    }

    var locked: Boolean
        get() = prefs.getBoolean("locked", true)
        set(value) { prefs.edit().putBoolean("locked", value).apply() }
}

data class SymbolKeyboardState(
    val category: SymbolCategory = SymbolCategory.Recent,
    val page: Int = 0,
    val locked: Boolean = true
) {
    fun items(recent: List<String>): List<String> = when (category) {
        SymbolCategory.Recent -> (recent + Common).distinct().take(64)
        SymbolCategory.Chinese -> Chinese
        SymbolCategory.English -> English
        SymbolCategory.Emoticon -> Emoticons
        SymbolCategory.Internet -> Internet
    }

    fun pageCount(recent: List<String>): Int = ((items(recent).size + 15) / 16).coerceAtLeast(1)

    fun visibleItems(recent: List<String>): List<String> = items(recent)
        .drop(page.coerceIn(0, pageCount(recent) - 1) * 16).take(16)

    fun navigate(route: String, recent: List<String>): SymbolKeyboardState = when {
        route == SymbolKeyboard.Name -> copy(category = SymbolCategory.Recent, page = 0)
        route == SymbolKeyboard.Lock -> copy(locked = !locked)
        route == SymbolKeyboard.Previous -> copy(page = (page - 1).coerceAtLeast(0))
        route == SymbolKeyboard.Next -> copy(page = (page + 1).coerceAtMost(pageCount(recent) - 1))
        route.startsWith(SymbolKeyboard.CategoryPrefix) -> runCatching {
            copy(category = SymbolCategory.valueOf(route.removePrefix(SymbolKeyboard.CategoryPrefix)), page = 0)
        }.getOrDefault(this)
        else -> this
    }

    companion object {
        val Common = listOf("？", ":", "/", "：", "，", "。", "…", "！", "丶", ".", "~", "……", "@", "$", "+", "-")
        val Chinese = listOf("，", "。", "？", "！", "：", "；", "、", "…", "……", "—", "——", "·", "（", "）", "《", "》",
            "“", "”", "‘", "’", "【", "】", "〈", "〉", "「", "」", "『", "』", "〔", "〕", "［", "］",
            "｛", "｝", "～", "￥", "＃", "％", "＆", "＊", "＋", "－", "＝", "／", "＼", "｜", "＠", "丶")
        val English = listOf("!", "?", ",", ".", ":", ";", "'", "\"", "(", ")", "[", "]", "{", "}", "<", ">",
            "@", "#", "$", "%", "^", "&", "*", "+", "-", "=", "/", "\\", "|", "_", "~", "`",
            "£", "€", "¥", "₩", "°", "±", "×", "÷", "©", "®", "™", "§", "¶", "•", "…", "...")
        val Emoticons = listOf("😊", "😂", "🥰", "😍", "😘", "🙂", "😎", "🤔", "😭", "😅", "👍", "👏", "🙏", "❤️", "🎉", "✨",
            "😀", "😁", "😆", "🤣", "😉", "🤗", "😋", "😴", "😮", "😤", "😡", "😢", "👌", "💪", "🌹", "🔥")
        val Internet = listOf("@", "#", "/", ".", ".com", ".cn", ".net", ".org", "http://", "https://", "www.", "_", "-", ":", "?", "&",
            "=", "%", "+", ".edu", ".gov", ".io", ".app", ".dev", "://", "mailto:", "ftp://", ".com.cn", ".co", ".hk", ".tv", "~")
    }
}
