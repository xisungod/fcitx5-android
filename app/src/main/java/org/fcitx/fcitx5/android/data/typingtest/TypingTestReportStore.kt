/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Private, bounded reports only. Export is a separate, explicit document-picker action. */
internal object TypingTestReportStore {
    private const val MAX_REPORTS = 3
    private const val MAX_BYTES = 512 * 1024
    private fun folder(context: Context) = File(context.noBackupFilesDir, "typing-tests")

    @Synchronized fun readLatest(context: Context): String? = reports(context).firstOrNull()?.let {
        if (it.length() !in 1..MAX_BYTES.toLong()) null
        else runCatching { AtomicFile(it).readFully().toString(Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized fun write(context: Context, id: Long, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val folder = folder(context)
        check(folder.isDirectory || folder.mkdirs())
        val atomic = AtomicFile(File(folder, "$id.json"))
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        reports(context).drop(MAX_REPORTS).forEach { AtomicFile(it).delete() }
    }

    @Synchronized fun clear(context: Context) {
        folder(context).listFiles()?.forEach { check(it.delete()) }
    }

    private fun reports(context: Context) = folder(context).listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9]+\\.json")) }
        .sortedByDescending { it.name.substringBefore('.').toLongOrNull() ?: 0L }
}
