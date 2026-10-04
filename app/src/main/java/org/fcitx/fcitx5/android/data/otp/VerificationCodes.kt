/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.otp

import kotlin.math.abs

/**
 * Finds one-time verification codes in SMS bodies or copied text and keeps the latest one
 * in memory only (never persisted, never sent anywhere; the app has no network permission).
 */
object VerificationCodes {

    enum class Source { Clipboard, Sms }

    data class Code(val code: String, val source: Source, val timestamp: Long)

    fun interface Listener {
        fun onCode(code: Code)
    }

    /** codes older than this are not offered any more */
    const val TTL_MS = 5 * 60 * 1000L

    private const val MAX_TEXT = 500

    private val keyword = Regex(
        "验证码|校验码|校驗碼|驗證碼|动态码|動態碼|动态密码|确认码|確認碼|激活码|安全码|随机码|短信码|认证码|授权码|登录码|" +
            "verification code|security code|one[- ]time|passcode|\\bcode\\b|\\botp\\b|\\bpin\\b",
        RegexOption.IGNORE_CASE
    )

    /** 4–8 digits not glued to other digits/letters; also 4–8 char codes like A1B2C3 */
    private val digits = Regex("(?<![0-9A-Za-z])[0-9]{4,8}(?![0-9A-Za-z])")
    private val alnum = Regex("(?<![0-9A-Za-z])(?=[A-Za-z0-9]*[0-9])(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{4,8}(?![0-9A-Za-z])")

    /** money, dates and times next to a number are never the code */
    private val unitAfter = Regex("^\\s*(元|块|万|分钟|分|秒|小时|天|年|月|日|号|%|位|次|个|岁|kg|km|mb|gb)", RegexOption.IGNORE_CASE)
    private val unitBefore = Regex("(¥|￥|\\$|尾号|尾號|账号|帳號|卡号|卡號|手机|手機|电话|電話)\\s*$")

    fun extract(text: CharSequence?): String? {
        if (text.isNullOrBlank() || text.length > MAX_TEXT) return null
        val s = text.toString()
        val keys = keyword.findAll(s).toList()
        if (keys.isEmpty()) return null
        val candidates = (digits.findAll(s) + alnum.findAll(s))
            .filter { m ->
                val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 6))
                val before = s.substring(maxOf(0, m.range.first - 4), m.range.first)
                !unitAfter.containsMatchIn(after) && !unitBefore.containsMatchIn(before) &&
                    !(m.value.length == 4 && m.value.toIntOrNull()?.let { it in 1990..2099 } == true &&
                        Regex("^\\s*[-/年.]").containsMatchIn(after))
            }
            .distinctBy { it.range.first }
            .toList()
        if (candidates.isEmpty()) return null
        // the candidate closest to a keyword wins; digits beat letters, codes after the keyword beat codes before
        return candidates.minByOrNull { m ->
            val distance = keys.minOf { k ->
                when {
                    m.range.first > k.range.last -> m.range.first - k.range.last
                    m.range.last < k.range.first -> (k.range.first - m.range.last) * 2 + 4
                    else -> abs(m.range.first - k.range.first)
                }
            }
            distance * 4 + (if (m.value.all(Char::isDigit)) 0 else 3)
        }?.value
    }

    @Volatile
    var latest: Code? = null
        private set

    // weak: input views come and go; each keeps its own listener field alive
    private val listeners: MutableSet<Listener> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    fun addListener(l: Listener) = synchronized(listeners) { listeners.add(l) }

    fun removeListener(l: Listener) = synchronized(listeners) { listeners.remove(l) }

    fun publish(code: String, source: Source, now: Long = System.currentTimeMillis()) {
        val c = Code(code, source, now)
        latest = c
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it.onCode(c) }
    }

    fun fresh(now: Long = System.currentTimeMillis()): Code? =
        latest?.takeIf { now - it.timestamp in 0..TTL_MS }

    fun consume() {
        latest = null
    }
}
