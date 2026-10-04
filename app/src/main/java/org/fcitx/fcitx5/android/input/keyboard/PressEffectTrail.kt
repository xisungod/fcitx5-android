/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Fixed storage: both expired entries and full-queue eviction reuse the existing objects. */
internal class PressEffectTrail(capacity: Int) {
    class Ripple {
        var x = 0f
        var y = 0f
        var flashWidth = 0f
        var flashHeight = 0f
        var keyFill = false
        var keyId = -1
        var pointerId = 0
        var colorIndex = 0
        /** Continuous position on the neon wheel; wrapping rebases all live sources together. */
        var colorPosition = 0f
        var shape = 0
        var echoShape = 0
        var rotation = 0f
        var spin = 0f
        var size = 1f
        var stretchX = 1f
        var stretchY = 1f
        var driftX = 0f
        var driftY = 0f
        var start = 0L
        var releasedAt = -1L
        /** Legacy layered renderer's gain. The shared field uses bounded spatial exposure. */
        var lightGain = 1f
        /** Legacy candidate-shoulder gain; V15 samples the same field above the keys. */
        var bridgeGain = 1f

        fun copyFrom(other: Ripple) {
            x = other.x; y = other.y
            flashWidth = other.flashWidth; flashHeight = other.flashHeight
            keyFill = other.keyFill; keyId = other.keyId; pointerId = other.pointerId
            colorIndex = other.colorIndex; colorPosition = other.colorPosition
            shape = other.shape; echoShape = other.echoShape
            rotation = other.rotation; spin = other.spin; size = other.size
            stretchX = other.stretchX; stretchY = other.stretchY
            driftX = other.driftX; driftY = other.driftY
            start = other.start; releasedAt = other.releasedAt; lightGain = other.lightGain
            bridgeGain = other.bridgeGain
        }
    }

    init { require(capacity > 0) }
    private val slots = Array(capacity) { Ripple() }
    var size = 0
        private set

    operator fun get(index: Int): Ripple {
        require(index in 0 until size)
        return slots[index]
    }

    fun findKey(keyId: Int): Ripple? {
        if (keyId < 0) return null
        for (i in 0 until size) if (slots[i].keyId == keyId) return slots[i]
        return null
    }

    fun obtain(now: Long): Ripple {
        if (size == slots.size) removeAt(0)
        return slots[size++].also { it.start = now }
    }

    fun removeAt(index: Int) {
        val free = slots[index]
        for (i in index until size - 1) slots[i] = slots[i + 1]
        slots[--size] = free
    }

    fun expire(now: Long, duration: Long) {
        var i = 0
        while (i < size) {
            if (now - slots[i].start >= duration) removeAt(i) else i++
        }
    }

    /** Held faces do not prevent later, finished faces from returning to the free pool. */
    fun expireWhile(done: (Ripple) -> Boolean) {
        var i = 0
        while (i < size) {
            if (done(slots[i])) removeAt(i) else i++
        }
    }

    fun clear() { size = 0 }
}
