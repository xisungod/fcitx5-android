/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.neural

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MiniRbtModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val root: File) {
        val files = linkedMapOf(
            "model.onnx" to ByteArray(200) { it.toByte() },
            "vocab.txt" to "[PAD]\n[UNK]\n你\n好\n".toByteArray(),
            "LICENSE" to "Apache-2.0".toByteArray(),
            "MODEL_CARD.md" to "Test package; not real inference weights".toByteArray(),
            "manifest.json" to "{\"model\":\"minirbt-h256\"}".toByteArray()
        )
        val archive = zip(files)
        var enabled = false
        val assetReads = AtomicInteger()
        val networkRequests = AtomicInteger()
        val directory = File(root, "model")

        fun spec(bytes: ByteArray = archive) = MiniRbtPackageSpec(
            "https://github.com/xisungod/fcitx5-android/releases/download/model/package.zip",
            digest(bytes), bytes.size.toLong(),
            files.mapValues { (_, bytes) -> MiniRbtExpectedFile(bytes.size.toLong(), digest(bytes)) }
        )

        fun repository(
            bundled: Boolean = false,
            bytes: ByteArray = archive,
            downloader: (suspend (File, (Int) -> Unit) -> Unit)? = null
        ) = MiniRbtModelRepository(directory, spec(bytes),
            bundledArchive = {
                assetReads.incrementAndGet()
                if (bundled) ByteArrayInputStream(bytes) else null
            },
            downloadArchive = downloader ?: { file, progress ->
                networkRequests.incrementAndGet()
                file.writeBytes(bytes)
                progress(100)
            },
            enabled = { enabled }, disable = { enabled = false })

        companion object {
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    files.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    }
                }
            }.toByteArray()
        }
    }

    private fun fixture() = Fixture(temporary.newFolder())

    @Test fun disabledScoringDoesNotReadBundledAssetOrAccessNetwork() = runBlocking {
        val fixture = fixture()
        val repository = fixture.repository(bundled = true)
        assertNull(repository.readyModel())
        assertEquals(0, fixture.assetReads.get())
        assertEquals(0, fixture.networkRequests.get())
        assertFalse(fixture.directory.exists())
        assertFalse(repository.state.value.ready)
    }

    @Test fun bundledModelCanBePreparedLocallyButDoesNotEnableRanking() = runBlocking {
        val fixture = fixture()
        val repository = fixture.repository(bundled = true)
        repository.bootstrapBundled()
        assertTrue(repository.state.value.ready)
        assertFalse(fixture.enabled)
        assertEquals(0, fixture.networkRequests.get())
        assertNull(repository.readyModel())
        fixture.enabled = true
        val loaded = requireNotNull(repository.readyModel())
        assertArrayEquals(fixture.files.getValue("model.onnx"), loaded.model.readBytes())
        assertArrayEquals(fixture.files.getValue("vocab.txt"), loaded.vocab.readBytes())
        assertArrayEquals(fixture.files.getValue("manifest.json"), loaded.manifest.readBytes())
        assertEquals(1, fixture.assetReads.get())
    }

    @Test fun explicitDownloadDoesNotAutoEnableAndConcurrentClicksShareInstalledPackage() = runBlocking {
        val fixture = fixture()
        val repository = fixture.repository()
        listOf(async(Dispatchers.Default) { repository.download() },
            async(Dispatchers.Default) { repository.download() }).awaitAll()
        assertTrue(repository.state.value.ready)
        assertEquals(1, fixture.networkRequests.get())
        assertEquals(0, fixture.assetReads.get())
        assertFalse(fixture.enabled)
        assertNull(repository.readyModel())
    }

    @Test fun offlineFailureLeavesNoPartialModelAndCanBeRetried() = runBlocking {
        val fixture = fixture()
        var fail = true
        val repository = fixture.repository(downloader = { file, progress ->
            file.writeBytes(byteArrayOf(1, 2, 3))
            if (fail) throw IOException("offline")
            file.writeBytes(fixture.archive); progress(100)
        })
        repository.download()
        assertEquals(MiniRbtModelStatus.ERROR, repository.state.value.status)
        assertEquals("download_failed", repository.state.value.error)
        assertFalse(repository.state.value.ready)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
        fail = false
        repository.download()
        assertTrue(repository.state.value.ready)
        assertFalse(fixture.enabled)
    }

    @Test fun unexpectedZipPathIsRejectedEvenWhenArchiveHashMatches() = runBlocking {
        val fixture = fixture()
        val malicious = Fixture.zip(fixture.files + ("../escaped.onnx" to byteArrayOf(7)))
        val repository = fixture.repository(bytes = malicious)
        repository.download()
        assertEquals(MiniRbtModelStatus.ERROR, repository.state.value.status)
        assertFalse(repository.state.value.ready)
        assertFalse(File(fixture.root, "escaped.onnx").exists())
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun incorrectFileHashIsRejectedEvenWhenArchiveHashMatches() = runBlocking {
        val fixture = fixture()
        val modified = Fixture.zip(fixture.files + ("model.onnx" to ByteArray(200) { 42 }))
        val repository = fixture.repository(bytes = modified)
        repository.download()
        assertEquals(MiniRbtModelStatus.ERROR, repository.state.value.status)
        assertFalse(repository.state.value.ready)
        assertFalse(fixture.directory.exists())
    }

    @Test fun corruptInstalledModelIsRecheckedAfterRestartAndDisablesRanking() = runBlocking {
        val fixture = fixture()
        fixture.repository().download()
        File(fixture.directory, "model.onnx").writeBytes(ByteArray(200) { 42 })
        fixture.enabled = true
        val restarted = fixture.repository()
        assertNull(restarted.readyModel())
        assertFalse(restarted.state.value.ready)
        assertFalse(fixture.enabled)
        assertFalse(fixture.directory.exists())
        assertEquals(1, fixture.networkRequests.get())
    }

    @Test fun interruptedDirectoryReplacementRecoversAndVerifiesPreviousPackage() = runBlocking {
        val fixture = fixture()
        fixture.repository().download()
        assertTrue(fixture.directory.renameTo(File(fixture.root, "model.backup")))
        fixture.enabled = true
        val restarted = fixture.repository()
        assertNotNull(restarted.readyModel())
        assertTrue(restarted.state.value.ready)
        assertTrue(fixture.enabled)
        assertEquals(0, fixture.assetReads.get())
        assertEquals(1, fixture.networkRequests.get())
        assertFalse(File(fixture.root, "model.backup").exists())
    }

    @Test fun savedOptInInitializesReadyFlowFromVerifiedLocalModelAfterRestart() = runBlocking {
        val fixture = fixture()
        fixture.repository().download()
        fixture.enabled = true
        val restarted = fixture.repository()
        assertFalse(restarted.state.value.ready)
        val notification = async { withTimeout(5_000) { restarted.state.first { it.ready } } }
        assertNotNull(restarted.readyModel())
        assertEquals(MiniRbtModelStatus.READY, notification.await().status)
        assertTrue(fixture.enabled)
        assertEquals(0, fixture.assetReads.get())
        assertEquals(1, fixture.networkRequests.get())
    }

    @Test fun savedOptInWithoutAnyModelIsDisabledWithoutDownloading() = runBlocking {
        val fixture = fixture()
        fixture.enabled = true
        val restarted = fixture.repository()
        assertNull(restarted.readyModel())
        assertFalse(fixture.enabled)
        assertFalse(restarted.state.value.ready)
        assertEquals(0, fixture.networkRequests.get())
    }

    @Test fun deletingWhileBundledModelIsBeingCopiedInvalidatesItImmediately() = runBlocking {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = MiniRbtModelRepository(fixture.directory, fixture.spec(),
            bundledArchive = {
                object : FilterInputStream(ByteArrayInputStream(fixture.archive)) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        started.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                        return super.read(bytes, offset, length)
                    }
                }
            },
            downloadArchive = { _, _ -> fail("Bundled preparation cannot download") },
            enabled = { fixture.enabled }, disable = { fixture.enabled = false })
        val copying = launch(Dispatchers.Default) { repository.bootstrapBundled() }
        withTimeout(5_000) { started.await() }
        fixture.enabled = true
        val deleting = launch(Dispatchers.Default) { repository.delete() }
        try {
            withTimeout(5_000) { repository.state.first { it.generation > 0 } }
            assertFalse(repository.state.value.ready)
            assertFalse(fixture.enabled)
        } finally { release.countDown() }
        copying.join()
        deleting.join()
        assertFalse(repository.state.value.ready)
        assertFalse(fixture.directory.exists())
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun deletingDuringDownloadCancelsItAndCannotReinstallOrEnableModel() = runBlocking {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        val repository = fixture.repository(downloader = { file, _ ->
            file.writeBytes(byteArrayOf(1))
            started.complete(Unit)
            waiting.await()
            file.writeBytes(fixture.archive)
        })
        val job = launch(Dispatchers.Default) { repository.download() }
        started.await()
        fixture.enabled = true
        repository.delete()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(fixture.enabled)
        assertFalse(repository.state.value.ready)
        assertNull(repository.readyModel())
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
        assertTrue(repository.state.value.generation > 0)
    }

    @Test fun cancelDownloadCleansStagingAndAllowsAnotherExplicitDownload() = runBlocking {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        var first = true
        val repository = fixture.repository(downloader = { file, _ ->
            if (first) {
                file.writeBytes(byteArrayOf(1)); started.complete(Unit); waiting.await()
            }
            file.writeBytes(fixture.archive)
        })
        val job = launch(Dispatchers.Default) { repository.download() }
        started.await()
        repository.cancelDownload()
        job.join()
        assertEquals(MiniRbtModelStatus.NOT_DOWNLOADED, repository.state.value.status)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
        first = false
        repository.download()
        assertTrue(repository.state.value.ready)
        assertFalse(fixture.enabled)
    }
}
