/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.diagnostics

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TouchDiagnosticWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationKeepsNewestCompleteRecordsWithinTotalCap() {
        val files = TouchDiagnosticFiles(temporary.newFolder(), maximumBytes = 80, chunkBytes = 24)
        repeat(8) { number -> assertTrue(files.append("{\"record\":$number}\n".toByteArray())) }
        val snapshot = files.snapshot(0, 0)
        assertTrue(snapshot.bytes <= 80)
        val lines = snapshot.content.toString(Charsets.UTF_8).trim().lines()
        assertEquals("{\"record\":7}", lines.last())
        assertFalse(lines.contains("{\"record\":0}"))
        assertEquals(lines.size, snapshot.recordCount)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test fun oversizedRecordIsRejectedWithoutCreatingAnyLog() {
        val directory = File(temporary.newFolder(), "private")
        val files = TouchDiagnosticFiles(directory)
        assertFalse(files.append(ByteArray(TouchDiagnosticFiles.MAXIMUM_RECORD_BYTES + 1)))
        assertFalse(directory.exists())
    }

    @Test fun activeChunkEvictionResetsItsCachedRecordCount() {
        val files = TouchDiagnosticFiles(temporary.newFolder(), maximumBytes = 8, chunkBytes = 128)
        listOf("a\n", "b\n", "c\n", "d\n", "e\n").forEach {
            assertTrue(files.append(it.toByteArray()))
        }
        val snapshot = files.snapshot(0, 0)
        assertEquals("e\n", snapshot.content.toString(Charsets.UTF_8))
        assertEquals(1, snapshot.recordCount)
        assertEquals(1, files.status(0).records)
    }

    @Test fun ageExpiryDeletesOldRecordsEvenWithoutANewAppend() {
        var time = 1_000_000L
        val files = TouchDiagnosticFiles(temporary.newFolder(), now = { time }, maximumAge = 100)
        files.append("{\"old\":true}\n".toByteArray())
        time += 101
        val snapshot = files.snapshot(0, 0)
        assertEquals(0, snapshot.bytes)
        assertEquals(0, snapshot.recordCount)
    }

    @Test fun appendingCannotExtendRetentionOfOldRecords() {
        var time = 1_000_000L
        val files = TouchDiagnosticFiles(temporary.newFolder(), now = { time }, maximumAge = 100)
        files.append("{\"old\":true}\n".toByteArray())
        time += 80
        files.append("{\"recent\":true}\n".toByteArray())
        time += 21
        assertEquals(0, files.snapshot(0, 0).bytes)
    }

    @Test fun queueOverloadDropsWithoutWaitingForWriter() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val writer = TouchDiagnosticWriter(TouchDiagnosticFiles(temporary.newFolder()),
            queueCapacity = 2, beforeAppend = {
                if (first.getAndSet(false)) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            })
        try {
            assertTrue(writer.append("one\n".toByteArray()) { true })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.append("two\n".toByteArray()) { true })
            assertTrue(writer.append("three\n".toByteArray()) { true })
            val before = System.nanoTime()
            assertFalse(writer.append("dropped\n".toByteArray()) { true })
            assertTrue("Queue admission cannot await storage", System.nanoTime() - before < 50_000_000)
            release.countDown()
            val snapshot = writer.snapshot()
            assertTrue(snapshot.droppedRecords >= 1)
            assertFalse(snapshot.content.toString(Charsets.UTF_8).contains("dropped"))
        } finally { release.countDown(); writer.close() }
    }

    @Test fun clearRevokesAnAlreadyDequeuedWriteAndQueuedOldWrites() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = TouchDiagnosticWriter(TouchDiagnosticFiles(temporary.newFolder()),
            beforeAppend = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        try {
            writer.append("old-active\n".toByteArray()) { true }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.append("old-queued\n".toByteArray()) { true }
            val clear = async(start = CoroutineStart.UNDISPATCHED) { writer.clear() }
            release.countDown()
            clear.await()
            assertEquals(0, writer.snapshot().bytes)
            writer.append("new\n".toByteArray()) { true }
            assertEquals("new\n", writer.snapshot().content.toString(Charsets.UTF_8))
        } finally { release.countDown(); writer.close() }
    }

    @Test fun editorRevocationDropsAnAlreadyDequeuedRecord() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val writer = TouchDiagnosticWriter(TouchDiagnosticFiles(temporary.newFolder()),
            beforeAppend = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        try {
            writer.append("sensitive-transition\n".toByteArray(), allowed::get)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            allowed.set(false)
            release.countDown()
            assertEquals(0, writer.snapshot().bytes)
        } finally { release.countDown(); writer.close() }
    }

    @Test fun exportUsesExactPreviewAndClearInvalidatesOldPreview() = runBlocking {
        val writer = TouchDiagnosticWriter(TouchDiagnosticFiles(temporary.newFolder()))
        try {
            writer.append("preview\n".toByteArray()) { true }
            val preview = writer.snapshot()
            writer.append("later\n".toByteArray()) { true }
            val output = ByteArrayOutputStream()
            writer.export(preview, output)
            assertEquals("preview\n", output.toString("UTF-8"))
            writer.clear()
            val clearedOutput = ByteArrayOutputStream()
            try {
                writer.export(preview, clearedOutput)
                fail("A cleared preview must not resurrect its contents through export")
            } catch (_: IllegalStateException) { }
            assertEquals(0, clearedOutput.size())
        } finally { writer.close() }
    }
}
