/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.diagnostics

import java.io.File
import java.io.OutputStream
import java.io.ByteArrayOutputStream

/** Only accessed by the diagnostic writer. No external storage, engine calls or uploads. */
internal class TouchDiagnosticFiles(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maximumBytes: Long = MAXIMUM_BYTES,
    private val chunkBytes: Int = CHUNK_BYTES,
    private val maximumAge: Long = MAXIMUM_AGE
) {
    companion object {
        const val MAXIMUM_BYTES = 2L * 1024 * 1024
        const val CHUNK_BYTES = 128 * 1024
        const val MAXIMUM_RECORD_BYTES = 96 * 1024
        const val MAXIMUM_AGE = 7L * 24 * 60 * 60 * 1000
    }

    private var sequence = 0L
    private var active: File? = null
    private val recordCounts = hashMapOf<String, Int>()

    private fun createdAt(file: File): Long = file.name.substringBefore('-').toLongOrNull()
        ?: file.lastModified()

    private fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".jsonl") }
        .sortedWith(compareBy<File> { createdAt(it) }.thenBy { it.name })

    fun prune() {
        val cutoff = now() - maximumAge
        // Expire by chunk creation, not last append; appending must not keep old traces forever.
        files().filter { createdAt(it) < cutoff }.forEach {
            check(it.delete()) { "Unable to expire diagnostic log" }
        }
        var retained = files()
        var total = retained.sumOf(File::length)
        while (total > maximumBytes && retained.isNotEmpty()) {
            val oldest = retained.first()
            check(oldest.delete()) { "Unable to expire diagnostic log" }
            retained = files()
            total = retained.sumOf(File::length)
        }
        if (active?.exists() != true) active = null
        recordCounts.keys.retainAll(files().map { it.name }.toSet())
    }

    fun append(line: ByteArray): Boolean {
        if (line.size > MAXIMUM_RECORD_BYTES || line.size.toLong() > maximumBytes) return false
        prune()
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create diagnostic directory" }
        var target = active
        if (target == null || !target.exists() || target.length() + line.size > chunkBytes) {
            do {
                target = File(directory, "${now()}-${(sequence++).toString().padStart(8, '0')}.jsonl")
            } while (target!!.exists())
            active = target
        }
        var total = files().sumOf(File::length)
        for (oldest in files()) {
            if (total + line.size <= maximumBytes) break
            val bytes = oldest.length()
            check(oldest.delete()) { "Unable to rotate diagnostic log" }
            recordCounts.remove(oldest.name)
            total -= bytes
            if (oldest == active) active = null
        }
        // A very small test cap can remove the active chunk. Recreate the same path safely.
        val oldCount = count(target!!)
        target.appendBytes(line)
        recordCounts[target.name] = oldCount + line.count { it == '\n'.code.toByte() }
        target.setLastModified(now())
        active = target
        return true
    }

    fun snapshot(epoch: Long, dropped: Long): TouchDiagnosticSnapshot {
        prune()
        val retained = files()
        val bytes = ByteArrayOutputStream().also { output ->
            retained.forEach { it.inputStream().use { input -> input.copyTo(output) } }
        }.toByteArray()
        return TouchDiagnosticSnapshot(epoch, bytes, retained.size,
            bytes.count { it == '\n'.code.toByte() }, retained.firstOrNull()?.let(::createdAt),
            retained.lastOrNull()?.lastModified(), dropped)
    }

    private fun count(file: File): Int = recordCounts.getOrPut(file.name) {
        if (!file.exists()) 0 else file.inputStream().buffered().use { input ->
            var result = 0
            val buffer = ByteArray(8192)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                for (index in 0 until length) if (buffer[index] == '\n'.code.toByte()) result++
            }
            result
        }
    }

    fun status(dropped: Long): TouchDiagnosticStatus {
        prune()
        val retained = files()
        return TouchDiagnosticStatus(retained.size, retained.sumOf(::count),
            retained.sumOf(File::length).toInt(), dropped)
    }

    fun clear() {
        files().forEach { check(it.delete()) { "Unable to clear diagnostic log" } }
        active = null
        recordCounts.clear()
    }
}

/** Immutable preview/export payload. Clear invalidates its epoch before deleting files. */
class TouchDiagnosticSnapshot internal constructor(
    internal val epoch: Long,
    internal val content: ByteArray,
    val fileCount: Int,
    val recordCount: Int,
    val oldestTime: Long?,
    val newestTime: Long?,
    val droppedRecords: Long
) {
    val bytes: Int get() = content.size
    internal fun writeTo(output: OutputStream) = output.write(content)
}
