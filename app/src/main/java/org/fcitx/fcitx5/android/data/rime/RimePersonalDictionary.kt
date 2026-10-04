/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.rime

import java.security.MessageDigest
import java.util.Locale

/** A bounded, plain-text personal dictionary. Source bytes never become YAML options. */
internal object RimePersonalDictionary {
    const val FILE_NAME = "xuancai_user.dict.yaml"
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    private val codePattern = Regex("[a-zv]+(?: [a-zv]+)*")
    data class Entry(val text: String, val code: String, val weight: Int) {
        val key get() = text to code
        fun line() = "$text\t$code\t$weight"
    }
    class InvalidLine(val lineNumber: Int) : IllegalArgumentException("Invalid dictionary line $lineNumber")

    fun parse(text: String): List<Entry> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Dictionary is too large" }
        val entries = linkedMapOf<Pair<String, String>, Entry>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val fields = line.split('\t')
            if (fields.size !in 2..3) throw InvalidLine(index + 1)
            val word = fields[0].trim()
            val code = fields[1].trim().lowercase(Locale.ROOT).replace('ü', 'v')
                .replace(Regex("\\s+"), " ")
            val weight = if (fields.size == 2) 100 else fields[2].trim().toIntOrNull()
                ?: throw InvalidLine(index + 1)
            if (word.isEmpty() || word.length > 64 || word.any { it.isISOControl() } ||
                code.length > 256 || !codePattern.matches(code) || weight !in 0..100_000)
                throw InvalidLine(index + 1)
            val entry = Entry(word, code, weight)
            entries[entry.key] = entry
            require(entries.size <= MAX_ENTRIES) { "Too many dictionary entries" }
        }
        require(entries.isNotEmpty()) { "Dictionary contains no entries" }
        return entries.values.toList()
    }

    fun readStored(text: String): List<Entry> {
        val separator = text.lineSequence().indexOfFirst { it.trim() == "..." }
        require(separator >= 0) { "Existing personal dictionary has an unsupported format" }
        val body = text.lineSequence().drop(separator + 1).joinToString("\n")
        return if (body.isBlank()) emptyList() else parse(body)
    }

    fun merge(existing: List<Entry>, incoming: List<Entry>): List<Entry> {
        val merged = linkedMapOf<Pair<String, String>, Entry>()
        (existing + incoming).forEach { merged[it.key] = it }
        require(merged.size <= MAX_ENTRIES) { "Too many dictionary entries" }
        return merged.values.sortedWith(compareBy<Entry> { it.code }.thenBy { it.text })
    }

    fun render(entries: List<Entry>): String {
        val body = entries.joinToString("\n") { it.line() }
        val version = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "# Rime dictionary\n# encoding: utf-8\n---\nname: xuancai_user\n" +
            "version: \"$version\"\nsort: by_weight\nuse_preset_vocabulary: false\n" +
            "import_tables:\n  - cn_dicts/xuancai_mobile\n...\n" + body + "\n"
    }
}
