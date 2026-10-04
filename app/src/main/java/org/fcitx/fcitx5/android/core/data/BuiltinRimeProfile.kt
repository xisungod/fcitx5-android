/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.data

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Initializes the bundled engine once without resetting later user choices. */
internal object BuiltinRimeProfile {
    private const val MIGRATED = "rime_profile_initialized"

    @Volatile
    private var initialInputMethod: String? = null

    /** Consume after Fcitx becomes ready, before restoring an Android subtype. */
    @Synchronized
    fun consumeInitialInputMethod(): String? = initialInputMethod.also {
        initialInputMethod = null
    }

    data class Update(val profile: String, val config: String)

    private val initialProfile = """
        [Groups/0]
        Name=Default
        Default Layout=us
        DefaultIM=rime

        [Groups/0/Items/0]
        Name=keyboard-us
        Layout=

        [Groups/0/Items/1]
        Name=rime
        Layout=

        [GroupOrder]
        0=Default
    """.trimIndent() + "\n"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences("builtin_components", Context.MODE_PRIVATE)
        val engineDir = context.getExternalFilesDir(null) ?: context.filesDir
        val profile = File(engineDir, "config/profile")
        val config = File(engineDir, "config/config")
        val update = plan(
            profile.takeIf { it.exists() }?.readText(),
            config.takeIf { it.exists() }?.readText(),
            preferences.getBoolean(MIGRATED, false)
        )
        if (update != null) {
            // Write the global flag first, so an interrupted profile write can
            // still be safely retried as the stock-profile migration next run.
            writeAtomic(config, update.config)
            writeAtomic(profile, update.profile)
            initialInputMethod = "rime"
        }
        preferences.edit().putBoolean(MIGRATED, true).commit()
    }

    /** Missing profiles get Rime on every locale. Only the old stock list migrates. */
    internal fun plan(profile: String?, config: String?, migrated: Boolean): Update? {
        if (profile == null) return Update(initialProfile, activateByDefault(config.orEmpty()))
        if (migrated || !isStockPinyinProfile(profile)) return null
        val updatedProfile = replaceValue(
            replaceValue(profile, "Groups/0", "DefaultIM", "rime"),
            "Groups/0/Items/1", "Name", "rime"
        )
        return Update(updatedProfile, activateByDefault(config.orEmpty()))
    }

    private fun isStockPinyinProfile(profile: String): Boolean {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in profile.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
            if (line.startsWith('[') && line.endsWith(']')) {
                val name = line.substring(1, line.length - 1)
                if (name in sections) return false
                current = linkedMapOf<String, String>().also { sections[name] = it }
            } else {
                val separator = line.indexOf('=')
                if (separator < 0) return false
                val section = current ?: return false
                val key = line.substring(0, separator).trim()
                if (key in section) return false
                section[key] = line.substring(separator + 1).trim()
            }
        }
        if (sections.keys != setOf("Groups/0", "Groups/0/Items/0", "Groups/0/Items/1", "GroupOrder")) {
            return false
        }
        val group = sections.getValue("Groups/0")
        if (group.keys != setOf("Name", "Default Layout", "DefaultIM") ||
            group["Default Layout"] != "us" || group["DefaultIM"] != "pinyin") return false
        val name = group["Name"].orEmpty()
        if (name.isEmpty() || sections["GroupOrder"] != mapOf("0" to name)) return false
        fun stockItem(section: String, engine: String): Boolean {
            val item = sections.getValue(section)
            return item.keys.all { it == "Name" || it == "Layout" } &&
                item["Name"] == engine && item["Layout"].isNullOrEmpty()
        }
        return stockItem("Groups/0/Items/0", "keyboard-us") &&
            stockItem("Groups/0/Items/1", "pinyin")
    }

    private fun activateByDefault(config: String): String =
        replaceValue(config, "Behavior", "ActiveByDefault", "True")

    /** Preserve comments and unrelated settings when replacing an INI value. */
    private fun replaceValue(text: String, section: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        var start = lines.indexOfFirst { it.trim() == "[$section]" }
        if (start < 0) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
            lines.add("[$section]")
            start = lines.lastIndex
        }
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].trim().startsWith('[')
        } ?: lines.size
        val existing = (start + 1 until end).firstOrNull {
            lines[it].substringBefore('=').trim() == key && '=' in lines[it]
        }
        if (existing == null) lines.add(start + 1, "$key=$value")
        else lines[existing] = "$key=$value"
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun writeAtomic(file: File, value: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }
}
