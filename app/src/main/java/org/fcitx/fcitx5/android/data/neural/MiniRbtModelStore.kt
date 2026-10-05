/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.neural

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

data class MiniRbtModelFiles(val model: File, val vocab: File, val manifest: File)

enum class MiniRbtModelStatus { NOT_DOWNLOADED, VERIFYING, DOWNLOADING, READY, ERROR }

data class ModelState(
    val status: MiniRbtModelStatus = MiniRbtModelStatus.NOT_DOWNLOADED,
    val ready: Boolean = false,
    val progress: Int? = null,
    val error: String? = null,
    val generation: Long = 0
)

/** No constructor model loading or automatic network request. Download is a settings action. */
class MiniRbtModelStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs get() = AppPrefs.getInstance().keyboard.miniRbtEnabled
    private val downloader = MiniRbtHttpsDownloader(MiniRbtModelPackage.spec)
    private val repository = MiniRbtModelRepository(
        File(appContext.filesDir, "neural_models/minirbt-h256-v1"), MiniRbtModelPackage.spec,
        bundledArchive = {
            try { appContext.assets.open(MiniRbtModelPackage.BUNDLED_ASSET) }
            catch (_: FileNotFoundException) { null }
        },
        downloadArchive = downloader::download,
        interruptDownload = downloader::interrupt,
        enabled = { prefs.getValue() },
        disable = { prefs.setValue(false) }
    )

    val state: StateFlow<ModelState> get() = repository.state

    /** Called by the scorer only after opt-in; packaged tests still start disabled. */
    suspend fun readyModel(): MiniRbtModelFiles? = repository.readyModel()

    /** Opening this settings page may prepare a bundled test model, but never downloads one. */
    suspend fun bootstrapBundled() = repository.bootstrapBundled()

    suspend fun download() = repository.download()
    suspend fun cancelDownload() = repository.cancelDownload()
    suspend fun delete() = repository.delete()

    companion object {
        @Volatile @Keep private var instance: MiniRbtModelStore? = null
        fun get(context: Context): MiniRbtModelStore = instance ?: synchronized(this) {
            instance ?: MiniRbtModelStore(context).also { instance = it }
        }
    }
}

/** Separate I/O boundary permits fault, cancellation and corrupt-package tests without networking. */
internal class MiniRbtModelRepository(
    private val directory: File,
    private val spec: MiniRbtPackageSpec,
    private val bundledArchive: () -> InputStream?,
    private val downloadArchive: suspend (File, (Int) -> Unit) -> Unit,
    private val interruptDownload: () -> Unit = {},
    private val enabled: () -> Boolean,
    private val disable: () -> Unit
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private val epoch = AtomicLong()
    private val mutableState = MutableStateFlow(ModelState())
    val state = mutableState.asStateFlow()
    @Volatile private var activeDownload: Job? = null
    private var cachedFiles: MiniRbtModelFiles? = null

    suspend fun readyModel(): MiniRbtModelFiles? {
        if (!enabled()) return null
        bootstrapBundled()
        return mutex.withLock { if (enabled() && state.value.ready) cachedFiles else null }
    }

    suspend fun bootstrapBundled() = withContext(Dispatchers.IO) {
        val operation = epoch.get()
        mutex.withLock {
            if (cachedFiles != null && state.value.ready) return@withLock
            if (operation != epoch.get()) return@withLock
            try {
                spec.validate()
                setState(MiniRbtModelStatus.VERIFYING, operation = operation)
                // A process death between directory renames leaves the previous verified package.
                val backup = File(directory.parentFile, "${directory.name}.backup")
                if (!directory.exists() && backup.isDirectory) check(backup.renameTo(directory))
                if (directory.isDirectory) {
                    try {
                        cachedFiles = verifyDirectory(directory)
                        backup.deleteRecursively()
                        setState(MiniRbtModelStatus.READY, ready = true, operation = operation)
                        return@withLock
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        cachedFiles = null
                        disable()
                        directory.deleteRecursively()
                    }
                }
                val input = bundledArchive()
                if (input == null) {
                    disable()
                    setState(MiniRbtModelStatus.NOT_DOWNLOADED, operation = operation)
                    return@withLock
                }
                input.use {
                    val staging = stagingDirectory()
                    try {
                        val archive = File(staging, "download.zip")
                        copyBounded(it, archive, spec.archiveBytes)
                        install(archive, staging, operation)
                    } finally { staging.deleteRecursively() }
                }
            } catch (cancelled: CancellationException) {
                setState(MiniRbtModelStatus.NOT_DOWNLOADED, operation = operation)
                throw cancelled
            } catch (_: Exception) {
                cachedFiles = null
                disable()
                setState(MiniRbtModelStatus.ERROR, error = "invalid_model", operation = operation)
            }
        }
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        val operation = epoch.get()
        mutex.withLock {
            if (operation != epoch.get()) return@withLock
            if (cachedFiles != null && state.value.ready) return@withLock
            activeDownload = currentCoroutineContext()[Job]
            var staging: File? = null
            try {
                spec.validate()
                setState(MiniRbtModelStatus.DOWNLOADING, progress = 0, operation = operation)
                val prepared = stagingDirectory()
                staging = prepared
                val archive = File(prepared, "download.zip")
                downloadArchive(archive) { progress ->
                    setState(MiniRbtModelStatus.DOWNLOADING,
                        progress = progress.coerceIn(0, 100), operation = operation)
                }
                install(archive, prepared, operation)
            } catch (cancelled: CancellationException) {
                setState(MiniRbtModelStatus.NOT_DOWNLOADED, operation = operation)
                throw cancelled
            } catch (error: Exception) {
                cachedFiles = null
                disable()
                setState(MiniRbtModelStatus.ERROR,
                    error = if (error is IOException) "download_failed" else "invalid_model",
                    operation = operation)
            } finally {
                staging?.deleteRecursively()
                activeDownload = null
            }
        }
    }

    suspend fun cancelDownload() {
        synchronized(stateLock) { epoch.incrementAndGet() }
        interruptDownload()
        activeDownload?.cancelAndJoin()
        mutex.withLock {
            setState(if (cachedFiles == null) MiniRbtModelStatus.NOT_DOWNLOADED else MiniRbtModelStatus.READY,
                ready = cachedFiles != null)
        }
    }

    suspend fun delete() {
        disable()
        synchronized(stateLock) {
            val operation = epoch.incrementAndGet()
            // Invalidation and READY publication are atomic: a stale install cannot revive it.
            mutableState.value = ModelState(generation = operation)
        }
        interruptDownload()
        activeDownload?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                cachedFiles = null
                directory.deleteRecursively()
                File(directory.parentFile, "${directory.name}.backup").deleteRecursively()
                setState(MiniRbtModelStatus.NOT_DOWNLOADED)
            }
        }
    }

    private fun setState(status: MiniRbtModelStatus, ready: Boolean = false,
        progress: Int? = null, error: String? = null, operation: Long = epoch.get()) {
        synchronized(stateLock) {
            if (operation == epoch.get()) {
                mutableState.value = ModelState(status, ready, progress, error, operation)
            }
        }
    }

    private fun stagingDirectory(): File {
        val parent = requireNotNull(directory.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        // Interrupted downloads are never resumed or trusted; an explicit operation starts afresh.
        parent.listFiles()?.filter { it.name.startsWith("minirbt-") && it.name.endsWith(".staging") }
            ?.forEach { it.deleteRecursively() }
        return File.createTempFile("minirbt-", ".staging", parent).apply {
            check(delete() && mkdir())
        }
    }

    private suspend fun install(archive: File, staging: File, operation: Long) {
        check(archive.length() == spec.archiveBytes && sha256(archive) == spec.archiveSha256)
        currentCoroutineContext().ensureActive()
        check(operation == epoch.get())
        setState(MiniRbtModelStatus.VERIFYING, operation = operation)
        val extracted = File(staging, "files").apply { check(mkdir()) }
        val seen = mutableSetOf<String>()
        ZipInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = input.nextEntry ?: break
                val expected = requireNotNull(spec.files[entry.name])
                require(!entry.isDirectory && seen.add(entry.name))
                // Exact, flat whitelist also rejects traversal, absolute paths and duplicates.
                copyBounded(input, File(extracted, entry.name), expected.bytes)
                input.closeEntry()
            }
        }
        check(seen == spec.files.keys)
        verifyDirectory(extracted)
        currentCoroutineContext().ensureActive()
        check(operation == epoch.get())
        val backup = File(directory.parentFile, "${directory.name}.backup")
        backup.deleteRecursively()
        if (directory.exists()) check(directory.renameTo(backup))
        try {
            check(extracted.renameTo(directory))
        } catch (error: Exception) {
            backup.renameTo(directory)
            throw error
        }
        backup.deleteRecursively()
        cachedFiles = modelFiles(directory)
        setState(MiniRbtModelStatus.READY, ready = true, operation = operation)
    }

    private suspend fun verifyDirectory(folder: File): MiniRbtModelFiles {
        check(folder.list()?.toSet() == spec.files.keys)
        spec.files.forEach { (name, expected) ->
            currentCoroutineContext().ensureActive()
            val file = File(folder, name)
            check(file.isFile && file.canonicalFile.parentFile == folder.canonicalFile)
            check(file.length() == expected.bytes && sha256(file) == expected.sha256)
        }
        return modelFiles(folder)
    }

    private fun modelFiles(folder: File) = MiniRbtModelFiles(File(folder, "model.onnx"),
        File(folder, "vocab.txt"), File(folder, "manifest.json"))

    private suspend fun copyBounded(input: InputStream, file: File, expectedBytes: Long) {
        var count = 0L
        val buffer = ByteArray(32 * 1024)
        file.outputStream().buffered().use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                require(count <= expectedBytes)
                output.write(buffer, 0, read)
            }
        }
        check(count == expectedBytes)
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Streams an immutable public package; never accepts text or prediction data. */
internal class MiniRbtHttpsDownloader(private val spec: MiniRbtPackageSpec) {
    @Volatile private var connection: HttpURLConnection? = null

    fun interrupt() { connection?.disconnect() }

    suspend fun download(target: File, progress: (Int) -> Unit) {
        var url = URL(spec.url)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(url.protocol == "https")
            require(url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val opened = url.openConnection() as HttpURLConnection
            connection = opened
            opened.instanceFollowRedirects = false
            opened.connectTimeout = 15_000
            opened.readTimeout = 15_000
            opened.setRequestProperty("Accept", "application/octet-stream")
            try {
                val response = opened.responseCode
                if (response in setOf(301, 302, 303, 307, 308)) {
                    url = URL(url, requireNotNull(opened.getHeaderField("Location")))
                    return@repeat
                }
                if (response != HttpURLConnection.HTTP_OK) throw IOException("Model download unavailable")
                val announced = opened.contentLengthLong
                if (announced > 0 && announced != spec.archiveBytes) throw IOException("Unexpected package size")
                var copied = 0L
                val buffer = ByteArray(32 * 1024)
                opened.inputStream.use { input ->
                    target.outputStream().buffered().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            copied += read
                            require(copied <= spec.archiveBytes)
                            output.write(buffer, 0, read)
                            progress((copied * 100 / spec.archiveBytes).toInt())
                        }
                    }
                }
                check(copied == spec.archiveBytes)
                return
            } finally {
                opened.disconnect()
                if (connection === opened) connection = null
            }
        }
        throw IOException("Too many download redirects")
    }
}
