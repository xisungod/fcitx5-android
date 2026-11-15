/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Reference-counted model ownership with one cancellable idle unload, never one sleeper per use. */
internal class IdleDictationModelCache<T : Any>(
    private val create: () -> T,
    private val destroy: (T) -> Unit,
    private val scheduler: ScheduledExecutorService,
    private val idleMillis: Long = 120_000L
) {
    private val lock = Any()
    private var loaded: T? = null
    private var users = 0
    private var token = 0L
    private var idleTask: ScheduledFuture<*>? = null
    private var releaseWhenIdle = false

    fun acquire(): T = synchronized(lock) {
        token++
        idleTask?.cancel(false)
        idleTask = null
        val value = loaded ?: create().also { loaded = it }
        users++
        value
    }

    fun release() {
        synchronized(lock) {
            if (users == 0) return
            users--
            if (users != 0 || loaded == null) return
            val expected = ++token
            idleTask?.cancel(false)
            idleTask = scheduler.schedule({ unload(expected) },
                if (releaseWhenIdle) 0L else idleMillis, TimeUnit.MILLISECONDS)
        }
    }

    /** Safe on the UI thread even if native construction currently holds the model lock. */
    fun trim() {
        scheduler.execute {
            synchronized(lock) {
                releaseWhenIdle = true
                if (users != 0) return@execute
                close(detach())
            }
        }
    }

    private fun unload(expected: Long) {
        synchronized(lock) {
            if (users != 0 || token != expected) return
            // A new cold acquire must not allocate a second native model while release
            // is still freeing the old one. This lock is never awaited by a UI callback.
            close(detach())
        }
    }

    private fun detach(): T? {
        token++
        idleTask?.cancel(false)
        idleTask = null
        releaseWhenIdle = false
        return loaded.also { loaded = null }
    }

    private fun close(value: T?) {
        // Native release failures must not escape onto the application or recording thread.
        if (value != null) runCatching { destroy(value) }
    }
}
