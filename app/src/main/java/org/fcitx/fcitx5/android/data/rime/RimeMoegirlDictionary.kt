/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.rime

import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Optional MediaWiki data is user-selected, separate from both main and personal dictionaries. */
internal object RimeMoegirlDictionary {
    const val ID = "axiang_moegirl"
    const val DICTIONARY_FILE = "$ID.dict.yaml"
    const val CONFIG_FILE = "$ID.yaml"
    const val RELEASE = "20261024"
    const val RELEASE_URL = "https://github.com/outloudvi/mw2fcitx/releases/tag/$RELEASE"
    const val LICENSE_URL = "https://github.com/outloudvi/mw2fcitx/blob/pkg-moegirl/LICENSE.content"
    private const val SOURCE_BYTES = 4_121_164
    private const val SHA256 = "f7ed0e76e6bab529c43ff1fb59252e003267c4a339d83972b5b112d025e1b314"
    private const val ACTIVE = "# AXiang optional Moegirl dictionary: enabled"
    private val header = "---\nname: moegirl\nsort: by_weight\nversion: 0.0.1\n...\n".toByteArray()

    data class State(val installed: Boolean, val enabled: Boolean)
    class InvalidRelease : IllegalArgumentException("Only the verified Moegirl dictionary release is supported")

    fun state(directory: File): State {
        val installed = listOf(DICTIONARY_FILE, "$ID.schema.yaml", "${ID}_t9.schema.yaml").all {
            directory.resolve(it).isFile
        }
        val config = directory.resolve(CONFIG_FILE)
        val enabled = installed && config.isFile && config.length() <= 8192 &&
            config.bufferedReader().use { it.readLine() == ACTIVE }
        return State(installed, enabled)
    }

    fun install(directory: File, source: InputStream) {
        // This release is 4.1 MB and exceeds the personal dictionary's row cap.
        // Read exactly the pinned length before processing any YAML; no selected
        // metadata, YAML directives or external references are passed to Rime.
        val data = ByteArray(SOURCE_BYTES)
        var offset = 0
        while (offset < data.size) {
            val count = source.read(data, offset, data.size - offset)
            if (count < 0) throw InvalidRelease()
            if (count == 0) {
                val next = source.read()
                if (next < 0) throw InvalidRelease()
                data[offset++] = next.toByte()
            } else offset += count
        }
        if (source.read() != -1 ||
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } != SHA256 ||
            header.indices.any { data[it] != header[it] }) throw InvalidRelease()
        check(directory.isDirectory || directory.mkdirs())
        // These schemas are dependency-only. Neither replaces a user file nor
        // appears as an input method. Full spelling and T9 share one word table.
        atomicWrite(directory.resolve("$ID.schema.yaml"), schema(t9 = false).toByteArray())
        atomicWrite(directory.resolve("${ID}_t9.schema.yaml"), schema(t9 = true).toByteArray())
        val attribution = """# Moegirlpedia contributors and outloudvi/mw2fcitx
            |# Source: $RELEASE_URL
            |# Content licence: CC BY-NC-SA ($LICENSE_URL)
            |# Modified by AXiang: dictionary header renamed for isolated local import.
            |---
            |name: $ID
            |version: "$RELEASE"
            |sort: by_weight
            |use_preset_vocabulary: false
            |...
            |""".trimMargin()
        atomicWrite(directory.resolve(DICTIONARY_FILE)) { output ->
            output.write(attribution.toByteArray())
            output.write(data, header.size, data.size - header.size)
        }
    }

    fun setEnabled(directory: File, enabled: Boolean) {
        val previous = state(directory)
        if (enabled) check(previous.installed) { "Moegirl dictionary is not installed" }
        if (enabled == previous.enabled) return
        check(directory.isDirectory || directory.mkdirs())
        val patchFile = directory.resolve(CONFIG_FILE)
        val oldModified = patchFile.lastModified()
        atomicWrite(patchFile, config(enabled).toByteArray())
        // Rime tracks include timestamps with second-level granularity.
        if (oldModified > 0 && patchFile.lastModified() / 1000 <= oldModified / 1000) {
            patchFile.setLastModified((oldModified / 1000 + 1) * 1000)
        }
    }

    internal fun config(enabled: Boolean): String {
        if (!enabled) return "qwerty: {}\nt9: {}\n"
        fun StringBuilder.addPatch(layout: String, prism: String) {
            appendLine("$layout:")
            appendLine("  schema/dependencies/+: [$prism]")
            appendLine("  engine/translators/+: [script_translator@$ID]")
            appendLine("  $ID:")
            appendLine("    dictionary: $ID")
            appendLine("    prism: $prism")
            appendLine("    enable_completion: false")
            appendLine("    enable_word_completion: false")
            appendLine("    enable_sentence: false")
            appendLine("    enable_user_dict: false")
            appendLine("    initial_quality: 0.2")
            if (layout == "t9") {
                appendLine("    spelling_hints: 32")
                appendLine("    always_show_comments: true")
            }
        }
        return buildString {
            appendLine(ACTIVE)
            addPatch("qwerty", ID)
            addPatch("t9", "${ID}_t9")
        }
    }

    private fun schema(t9: Boolean): String {
        val id = if (t9) "${ID}_t9" else ID
        return buildString {
            appendLine("schema:")
            appendLine("  schema_id: $id")
            appendLine("  name: Moegirlpedia (local)")
            appendLine("  version: \"$RELEASE\"")
            appendLine("engine:")
            appendLine("  processors: [speller, selector, express_editor]")
            appendLine("  segmentors: [abc_segmentor]")
            appendLine("  translators: [script_translator]")
            appendLine("speller:")
            if (t9) appendLine("  __include: rime_ice_t9.schema:/speller") else {
                appendLine("  alphabet: abcdefghijklmnopqrstuvwxyz")
                appendLine("  delimiter: \" '\"")
            }
            appendLine("translator:")
            appendLine("  dictionary: $ID")
            appendLine("  prism: $id")
            appendLine("  enable_completion: false")
            appendLine("  enable_sentence: false")
            appendLine("  enable_user_dict: false")
        }
    }

    private fun atomicWrite(path: File, bytes: ByteArray) = atomicWrite(path) { it.write(bytes) }

    private fun atomicWrite(path: File, write: (OutputStream) -> Unit) {
        val file = AtomicFile(path)
        val output = file.startWrite()
        try {
            write(output)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
