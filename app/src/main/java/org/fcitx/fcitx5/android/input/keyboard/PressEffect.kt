/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Colourful key press effect in the spirit of Samsung Keys Cafe "keyboard colour effects":
 * every press releases a soft, feathered water ripple of a random palette colour
 * with a random irregular shape, which spreads over the neighbouring keys and fades out.
 *
 * The diffuse wave is drawn across the keyboard. Individual key faces draw their
 * own colour between their background and character, so the key stays bright and
 * its label remains crisp without covering the whole keyboard in colour.
 *
 * Exact key layouts use one fixed RGB field with gently bending source fronts.
 * The legacy layered mode alone uses cached white masks tinted per ripple.
 */
internal class PressEffect(
    private val host: View,
    palette: IntArray,
    isDark: Boolean,
    sizePercent: Int,
    expansionTimeMs: Int,
    fadeOutTimeMs: Int,
    overKeysPercent: Int,
    private val pressEnabled: Boolean,
    idleEnabled: Boolean,
    private val breathing: IdleBreathing,
    idleColors: IntArray,
    private val clock: () -> Long = UPTIME_CLOCK,
    randomIdleColors: Boolean = false,
    ignitionTimeMs: Int = 40,
    keyHoldTimeMs: Int = 260,
    keyRetreatTimeMs: Int = 480,
    private val keySurfaceEffects: Boolean = false,
    /** 0 = colour fills the key, 1 = glowing border, 2 = no key colour (only the spreading light) */
    private val keyColorStyle: Int = 0,
    private val keyCornerRadius: Float = 0f,
    /** 100 = the V09 reach; the Samsung reference keeps the coloured light around neighbouring keys */
    glowReachPercent: Int = 100,
    private val glowOnCandidates: Boolean = true,
    /** V13: key colour follows the finger (full while held, tail starts on release) */
    private val holdWhilePressed: Boolean = false,
    /** V13: the released key keeps its exact shape and dims (dark-theme Samsung trail) instead of shrinking */
    private val dimExit: Boolean = false,
    /** Legacy parameter retained for compatibility; V14 always uses fluid key-edge light. */
    private val ringWave: Boolean = false,
    /** V13: colours walk through the palette in typing order instead of jumping randomly */
    private val sequentialColors: Boolean = false,
    /** V13: fill the real rounded keycap, with a backlight hot spot and a faint outer halo */
    private val exactKeyShape: Boolean = false,
    /** V13: older waves share one brightness budget so the newest press stays in front */
    private val normalizeOldLight: Boolean = false,
    private val extensionStrength: Float = 1f,
    private val animationsAllowed: () -> Boolean = {
        !AppPrefs.getInstance().advanced.disableAnimation.getValue() &&
            (!org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.effectsFollowSystemAnimation.getValue() ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
    },
    glowBrightnessPercent: Int = 100,
    waveHoldTimeMs: Int = 0,
    coordinatePalette: Boolean = true,
    private val rippleShape: org.fcitx.fcitx5.android.data.theme.ThemePrefs.RippleShape =
        org.fcitx.fcitx5.android.data.theme.ThemePrefs.RippleShape.SoftMist,
    /** Floating motion keeps colour visible through the rise; Sam also has its own release tail. */
    private val keyFloatOpacity: ((Int) -> Float)? = null,
    samKeyHoldTimeMs: Int = 80,
    samKeyRetreatTimeMs: Int = 800
) {

    private val samGlare = rippleShape == org.fcitx.fcitx5.android.data.theme.ThemePrefs.RippleShape.Sam
    // Sam's black mask has no resting light, even when an older installation
    // retains its breathing preference for the other two effects.
    private val idleEnabled = idleEnabled && !samGlare
    private val lightLegends = isDark || samGlare
    private val colors: IntArray =
        (if (palette.isEmpty()) CYBERPUNK else palette).map { adapt(it, lightLegends) }.toIntArray()
    private val cyclicNeon = coordinatePalette && (palette.isEmpty() || palette.contentEquals(CYBERPUNK))
    private val coordinatedNeon = coordinatePalette && (cyclicNeon || PressColorPalette.isCoordinated(palette))
    private val sharedFieldEnabled = samGlare || (keySurfaceEffects && exactKeyShape)
    private val liquidKeyFaces = keySurfaceEffects && exactKeyShape && keyColorStyle == KEY_STYLE_FILL &&
        rippleShape == org.fcitx.fcitx5.android.data.theme.ThemePrefs.RippleShape.IrregularFluid

    private val density = host.resources.displayMetrics.density
    private val sizeScale = sizePercent.coerceIn(20, 400) / 100f
    // Keep the candidate-strip reach, but soften the oversized wash during fast typing.
    // Apply this to the radius so existing saved size settings also receive the adjustment.
    private val glowReach = glowReachPercent.coerceIn(10, 100) / 100f
    private val maxRadius: Float get() = max(BASE_RADIUS_DP * density, host.height * 1.15f) * sizeScale * 0.85f * glowReach
    private val flashRadius = FLASH_RADIUS_DP * density
    // Shape changes the spatial profile, while the visible timing controls keep
    // their actual millisecond meaning in every mode. Sam ignites immediately.
    private val expansionDuration = expansionTimeMs.coerceIn(100, 4000).toLong()
    private val ignitionDuration = if (samGlare) 0L else ignitionTimeMs.coerceIn(30, 300).toLong()
    private val waveHoldDuration = waveHoldTimeMs.coerceIn(0, 2000).toLong()
    private val fadeDuration = fadeOutTimeMs.coerceIn(100, 5000).toLong()
    private val fadeStart = ignitionDuration + expansionDuration + waveHoldDuration
    private val waveDuration = fadeStart + fadeDuration
    private val keyHoldDuration = keyHoldTimeMs.coerceIn(20, 1000).toLong()
    private val keyRetreatDuration = keyRetreatTimeMs.coerceIn(20, 5000).toLong()
    private val samKeyHoldDuration = samKeyHoldTimeMs.coerceIn(0, 1000).toLong()
    private val samKeyRetreatDuration = samKeyRetreatTimeMs.coerceIn(100, 5000).toLong()
    private val duration = max(waveDuration, keyHoldDuration + keyRetreatDuration)
    private val keyFlashTextures = KEY_FLASH_TEXTURES
    private val underStrength = if (lightLegends) 1f else 0.9f
    private val glowStrength = glowBrightnessPercent.coerceIn(0, 100) / 100f
    // Compatibility preference remains readable, but light never paints over glyphs.
    @Suppress("UNUSED_PARAMETER")
    private val legacyOverKeys = overKeysPercent

    /** returns (alpha, retreat 0..1) of the key face, or null once the face is gone */
    private fun keyEnvelope(r: PressEffectTrail.Ripple, now: Long): Float {
        if (samGlare) {
            envelopeRetreat = 0f
            if (r.releasedAt < 0L) return 0.92f
            val released = (now - r.releasedAt).coerceAtLeast(0L).toFloat()
            val tail = 1f - smoothstep(samKeyHoldDuration.toFloat(),
                (samKeyHoldDuration + samKeyRetreatDuration).toFloat(), released)
            val motion = if (holdWhilePressed && r.keyFill)
                keyFloatOpacity?.invoke(r.keyId)?.coerceIn(0f, 1f) ?: 0f else 0f
            // Geometry may land first or last depending on the motion settings.
            // Neither clock can cut off the other; subsequent keys own neither.
            return 0.92f * max(motion, tail)
        }
        if (holdWhilePressed && r.keyFill && keyFloatOpacity != null) {
            // The real KeyView supplies its own per-key elevation/fade state.
            envelopeRetreat = 0f
            return 0.92f * keyFloatOpacity.invoke(r.keyId).coerceIn(0f, 1f)
        }
        val elapsed = (now - r.start).coerceAtLeast(0L)
        if (!holdWhilePressed) {
            val retreat = smoothstep(keyHoldDuration.toFloat(), (keyHoldDuration + keyRetreatDuration).toFloat(), elapsed.toFloat())
            envelopeRetreat = retreat
            return if (elapsed >= duration) 0f else 0.92f * (1f - smoothstep(0.60f, 1f, retreat))
        }
        envelopeRetreat = 0f
        if (r.releasedAt < 0L) return 0.92f
        val tailStart = max(r.releasedAt, r.start + keyHoldDuration)
        if (now < tailStart) return 0.92f
        val p = ((now - tailStart).toFloat() / keyRetreatDuration).coerceIn(0f, 1f)
        if (p >= 1f) return 0f
        envelopeRetreat = if (dimExit) 0f else smoothstep(0f, 1f, p)
        // ease-out dimming: quick first drop, long soft tail = visible typing trail
        return 0.92f * (1f - p).pow(if (dimExit) 1.7f else 1f)
    }
    private var envelopeRetreat = 0f

    private fun expire(now: Long) {
        ripples.expire(now, waveDuration)
        var i = 0
        while (i < keyFaces.size) {
            if (keyEnvelope(keyFaces[i], now) <= 0f) {
                val keyId = keyFaces[i].keyId
                forgetKeyColor(keyId)
                ownedColorKeys.delete(keyId)
                keyFaces.removeAt(i)
            } else i++
        }
    }

    private val random = Random(SystemClock.uptimeMillis())
    // Travelling light and key faces have separate lifetimes. A busy wave queue cannot
    // erase a still-visible key, and repeated presses never stack old colours on a face.
    private val ripples = PressEffectTrail(MAX_RIPPLES)
    private val freshAttention = FloatArray(MAX_RIPPLES)
    private val neonField = if (sharedFieldEnabled) NeonField() else null
    private var fieldPreparedAt = Long.MIN_VALUE
    private val keyFaces = PressEffectTrail(MAX_KEY_FACES)
    private val heldFaces = android.util.SparseArray<PressEffectTrail.Ripple>()
    private val ownedColorKeys = android.util.SparseBooleanArray()
    private fun clearOwnedColors() {
        for (i in 0 until ownedColorKeys.size()) forgetKeyColor(ownedColorKeys.keyAt(i))
        ownedColorKeys.clear()
    }
    private val styles = Array(colors.size) {
        RipplePaints(colors[it], if (lightLegends) readableCapColor(colors[it]) else colors[it])
    }
    private var lastColorIndex = -1
    private var cyberColorPosition = 0f
    private var lastShapeIndex = -1
    private var frameTime = 0L
    private val choreographer = Choreographer.getInstance()
    private var callbackFrameId = Long.MIN_VALUE
    private var callbackFrameClock = 0L
    private var displayFrameId = Long.MIN_VALUE
    private var displayFrameClock = 0L
    private var displayFrameNanos: () -> Long = {
        // Injected clocks drive manual renders, without an Android traversal.
        if (clock === UPTIME_CLOCK) callbackFrameId else Long.MIN_VALUE
    }
    private fun drawClock(): Long {
        val frame = displayFrameNanos()
        val now = clock()
        // Production shares the timestamp sampled by its public FrameCallback;
        // manually rendered views keep their injected clock authoritative.
        if (frame == Long.MIN_VALUE) return now
        if (frame != displayFrameId) {
            displayFrameId = frame
            displayFrameClock = if (clock === UPTIME_CLOCK && frame == callbackFrameId) callbackFrameClock else now
        }
        return displayFrameClock
    }
    private var active = false
    // Cache all shaders once. A new colour pair is chosen near the dim point of each breath.
    private val idleStyles = if (randomIdleColors && idleColors.size > 1)
        Array(idleColors.size) { i -> IdleGlowPainter(idleColors[i], idleColors[(i + 3) % idleColors.size]) }
    else arrayOf(IdleGlowPainter(idleColors.getOrElse(0) { CYBERPUNK[0] },
        idleColors.getOrElse(1) { CYBERPUNK[4] }))
    private var idleStyleIndex = random.nextInt(idleStyles.size)
    private var previousIdlePhase = 1f
    var invalidateExtension: (() -> Unit)? = null
    var invalidateKeySurfaces: (() -> Unit)? = null
    private fun invalidateBoth() {
        host.invalidate()
        invalidateKeySurfaces?.invoke()
        invalidateExtension?.invoke()
    }
    private val endDisplayFrame = Runnable {
        // Posted during doFrame, this runs after that frame's traversal returns.
        // An unrelated/manual draw later must not inherit a stale frame clock.
        callbackFrameId = Long.MIN_VALUE
        displayFrameId = Long.MIN_VALUE
    }
    private val frameCallback = Choreographer.FrameCallback { nanos ->
        callbackFrameId = nanos
        callbackFrameClock = clock()
        if (canDraw() && animatorsEnabled()) {
            host.removeCallbacks(endDisplayFrame)
            host.post(endDisplayFrame)
            invalidateBoth()
        } else clear()
    }

    private fun canDraw() = active && host.isAttachedToWindow && host.isShown &&
        host.windowVisibility == View.VISIBLE

    fun setActive(value: Boolean) {
        active = value
        syncVisibility()
    }

    fun syncVisibility() {
        choreographer.removeFrameCallback(frameCallback)
        host.removeCallbacks(endDisplayFrame)
        callbackFrameId = Long.MIN_VALUE
        displayFrameId = Long.MIN_VALUE
        if (!canDraw() || !animatorsEnabled()) {
            ripples.clear()
            clearOwnedColors()
            keyFaces.clear()
            heldFaces.clear()
            breathing.stop()
        } else if (idleEnabled && !breathing.running) {
            breathing.resume(clock())
        }
        invalidateBoth()
    }

    private fun releasePointer(pointerId: Int, now: Long) {
        val face = heldFaces.get(pointerId)
        heldFaces.remove(pointerId)
        if (face != null) {
            var stillHeld = false
            for (i in 0 until heldFaces.size()) if (heldFaces.valueAt(i) === face) stillHeld = true
            if (!stillHeld && face.releasedAt < 0L) face.releasedAt = now
        }
        for (i in 0 until ripples.size) {
            val r = ripples[i]
            if (r.pointerId == pointerId && r.releasedAt < 0L) r.releasedAt = now
        }
    }

    /** Releasing one finger must leave the other pressed keys illuminated. */
    fun onRelease(pointerId: Int) {
        val now = clock()
        releasePointer(pointerId, now)
        breathing.activity(now, held = heldFaces.size() > 0)
        requestDraw()
    }

    /** Cancellation/final teardown releases every held pointer. */
    fun onRelease() {
        val now = clock()
        for (i in 0 until keyFaces.size) {
            val r = keyFaces[i]
            if (r.releasedAt < 0L) r.releasedAt = now
        }
        for (i in 0 until ripples.size) {
            val r = ripples[i]
            if (r.releasedAt < 0L) r.releasedAt = now
        }
        heldFaces.clear()
        breathing.activity(now, held = false)
        requestDraw()
    }

    private fun requestDraw() {
        choreographer.removeFrameCallback(frameCallback)
        if (!canDraw()) return
        choreographer.postFrameCallback(frameCallback)
        host.postInvalidateOnAnimation()
        invalidateKeySurfaces?.invoke()
        invalidateExtension?.invoke()
    }

    private fun scheduleFrame(delay: Long) {
        choreographer.removeFrameCallback(frameCallback)
        if (!canDraw() || !animatorsEnabled()) return
        if (delay == 0L) choreographer.postFrameCallback(frameCallback)
        else choreographer.postFrameCallbackDelayed(frameCallback, delay)
    }

    private class RipplePaints(color: Int, capColor: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        // Immediate feedback even while the first set of textures is warming up.
        val luminance = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
        val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.FILL
            it.color = color
        }
        // The whole cap receives one uniformly toned colour. Keeping this paint
        // separate leaves the under-key neon fully saturated and bright.
        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.FILL
            it.color = capColor
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xCCFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.STROKE
            it.color = color
        }
        val liquidRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.STROKE
            it.strokeCap = Paint.Cap.ROUND
            // A small same-hue highlight, not a white stripe over the glyph.
            it.color = Color.rgb((Color.red(color) * 0.82f + 255f * 0.18f).toInt(),
                (Color.green(color) * 0.82f + 255f * 0.18f).toInt(),
                (Color.blue(color) * 0.82f + 255f * 0.18f).toInt())
        }
        val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(color, color and 0x00ffffff),
                null, Shader.TileMode.CLAMP
            )
        }
    }

    init {
        if (pressEnabled && !sharedFieldEnabled) Textures.ensure()
    }

    fun onPress(pressX: Float, pressY: Float, keyBounds: Rect? = null, highlightKey: Boolean = false, keyId: Int = -1, pointerId: Int = 0) {
        if (!canDraw() || !animatorsEnabled()) {
            forgetKeyColor(keyId)
            return
        }
        val now = clock()
        if (idleEnabled && !breathing.running) breathing.resume(now)
        breathing.activity(now, held = true)
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
        host.postInvalidateOnAnimation()
        invalidateKeySurfaces?.invoke()
        invalidateExtension?.invoke()
        if (!pressEnabled) {
            forgetKeyColor(keyId)
            return
        }
        if (!sharedFieldEnabled && Textures.ready == null) Textures.ensure()
        val previousColorIndex = lastColorIndex
        var index = if (sequentialColors) (lastColorIndex + 1).mod(colors.size)
            else if (cyclicNeon) PressColorPalette.nextCyberpunkIndex(lastColorIndex, random)
            else if (coordinatedNeon) PressColorPalette.nextFamilyIndex(lastColorIndex, colors.size, random)
            else random.nextInt(colors.size)
        if (!sequentialColors && !coordinatedNeon && colors.size > 1 && index == lastColorIndex) {
            index = (index + 1 + random.nextInt(colors.size - 1)) % colors.size
        }
        if (coordinatedNeon && !cyclicNeon) {
            // Bounded families have no end-to-start seam: both key selection and the
            // shared field stay on their chosen cyan/blue/purple (or warm) hue path.
            cyberColorPosition = index.toFloat()
        } else if (cyclicNeon) {
            var step = index - previousColorIndex
            if (step > colors.size / 2) step -= colors.size
            if (step < -colors.size / 2) step += colors.size
            cyberColorPosition = if (previousColorIndex < 0) index.toFloat() else cyberColorPosition + step
            // Keep float precision bounded without changing any live colour:
            // a common full-turn offset commutes with weighted interpolation.
            val turns = kotlin.math.floor(cyberColorPosition / colors.size) * colors.size
            if (turns != 0f) {
                cyberColorPosition -= turns
                for (i in 0 until ripples.size) ripples[i].colorPosition -= turns
                for (i in 0 until keyFaces.size) keyFaces[i].colorPosition -= turns
            }
        }
        lastColorIndex = index
        // Retire the old face before publishing this press's colour. A delayed
        // draw must not let an expired face remove the new preview tint.
        expire(now)
        lastPressColor = colors[index]
        if (keyId >= 0) {
            val colorNow = SystemClock.uptimeMillis()
            for (i in keyColorTimes.size() - 1 downTo 0) {
                if (colorNow - keyColorTimes.valueAt(i) > 2000L) forgetKeyColor(keyColorTimes.keyAt(i))
            }
            keyColors.put(keyId, colors[index])
            keyColorTimes.put(keyId, colorNow)
            ownedColorKeys.put(keyId, true)
        }
        val shapes = PressEffectMasks.SHAPES
        var selectedShape = random.nextInt(shapes)
        if (shapes > 1 && selectedShape == lastShapeIndex) selectedShape = (selectedShape + 1 + random.nextInt(shapes - 1)) % shapes
        lastShapeIndex = selectedShape
        // Sliding retargets only this finger; another finger can keep its own key held.
        releasePointer(pointerId, now)
        val wave = ripples.obtain(now).apply {
            this.pointerId = pointerId
            releasedAt = -1L
            lightGain = 1f
            bridgeGain = 1f
            keyFill = highlightKey && keyBounds != null
            this.keyId = keyId
            x = keyBounds?.exactCenterX() ?: pressX
            y = keyBounds?.exactCenterY() ?: pressY
            flashWidth = if (keyBounds != null) max(density, keyBounds.width().toFloat()) else flashRadius * 2f
            flashHeight = if (keyBounds != null) max(density, keyBounds.height().toFloat()) else flashRadius * 2f
            colorIndex = index
            colorPosition = cyberColorPosition
            shape = selectedShape
            echoShape = (selectedShape + 1 + random.nextInt(max(1, shapes - 1))) % shapes
            rotation = random.nextFloat() * 360f
            spin = (random.nextFloat() * 2f - 1f) * 24f
            size = 0.88f + random.nextFloat() * 0.24f
            stretchX = 0.82f + random.nextFloat() * 0.36f
            stretchY = 0.82f + random.nextFloat() * 0.36f
            driftX = (random.nextFloat() - 0.5f) * 22f * density
            driftY = (random.nextFloat() - 0.5f) * 16f * density
        }
        val face = keyFaces.findKey(keyId) ?: keyFaces.obtain(now)
        face.copyFrom(wave)
        if (!face.keyFill) {
            // Functions retain the soft feedback instead of introducing a pressed block.
            face.flashWidth = flashRadius * 2f
            face.flashHeight = flashRadius * 2f
            face.x = pressX
            face.y = pressY
        }
        heldFaces.put(pointerId, face)
        fieldPreparedAt = Long.MIN_VALUE
        displayFrameClock = max(displayFrameClock, now)
        host.postInvalidateOnAnimation()
        invalidateExtension?.invoke()
    }

    /** Both light layers stay under the key legends. */
    fun drawUnder(canvas: Canvas) {
        frameTime = drawClock()
        if (!canDraw() || !animatorsEnabled()) {
            clear()
            return
        }
        // IME views can be attached while an ancestor/window is still hidden. A later
        // visible draw is authoritative even if the earlier callback could not start it.
        if (idleEnabled && !breathing.running) breathing.resume(frameTime)
        expire(frameTime)
        val idleAlpha = if (idleEnabled) breathing.alpha(frameTime) else 0f
        if (idleAlpha > 0.001f) drawBreathing(canvas, idleAlpha)
        if (ripples.size > 0 || keyFaces.size > 0) draw(canvas, underStrength)
    }

    /** Mist faces rest while held; a visible liquid rim continues its local flow. */
    fun drawOver(canvas: Canvas) {
        if (!canDraw() || !animatorsEnabled()) return
        var changing = glowStrength > 0f && ripples.size > 0
        if (!changing) for (i in 0 until keyFaces.size) {
            val face = keyFaces[i]
            if ((!holdWhilePressed || face.releasedAt >= 0L || (liquidKeyFaces && face.keyFill)) &&
                keyEnvelope(face, frameTime) > 0f) {
                changing = true
                break
            }
        }
        if (changing) scheduleFrame(0L)
        else {
            choreographer.removeFrameCallback(frameCallback)
            breathing.nextFrameDelay(frameTime)?.let(::scheduleFrame)
        }
    }

    fun clear() {
        choreographer.removeFrameCallback(frameCallback)
        host.removeCallbacks(endDisplayFrame)
        ripples.clear()
        clearOwnedColors()
        keyFaces.clear()
        heldFaces.clear()
        fieldPreparedAt = Long.MIN_VALUE
        displayFrameId = Long.MIN_VALUE
        callbackFrameId = Long.MIN_VALUE
        breathing.stop()
    }

    private fun drawBreathing(canvas: Canvas, alpha: Float) {
        val phase = breathing.phase(frameTime)
        if (phase < previousIdlePhase && phase < 0.08f && idleStyles.size > 1) {
            idleStyleIndex = (idleStyleIndex + 1 + random.nextInt(idleStyles.size - 1)) % idleStyles.size
        }
        previousIdlePhase = phase
        idleStyles[idleStyleIndex].draw(canvas, host.width, host.height, alpha, phase)
    }

    /** Called inside the candidate bar, behind its text, with keyboard-relative coordinates. */
    fun drawExtension(canvas: Canvas) {
        if (!glowOnCandidates) return
        if (!canDraw() || !animatorsEnabled()) return
        frameTime = drawClock()
        if (ripples.size > 0) draw(canvas, underStrength * extensionStrength)
    }

    private fun drawKeyFlash(canvas: Canvas, r: PressEffectTrail.Ripple, style: RipplePaints,
        elapsed: Long, strength: Float, centerX: Float, centerY: Float) {
        // Give the pressed key its own colour first; no expansion until ignition finishes.
        // Hold the key colour while later presses light up; then shrink its coloured
        // area from a rounded key face to a soft round spot independently of the wave.
        val envelope = keyEnvelope(r, r.start + elapsed)
        if (envelope <= 0f) return
        val retreat = envelopeRetreat
        val keyAlpha = envelope * strength
        if (keyAlpha > 0.004f) {
            val saved = canvas.save()
            canvas.translate(centerX, centerY)
            if (r.keyFill && keyColorStyle == KEY_STYLE_BORDER) {
                drawBorderGlow(canvas, r, style, (keyAlpha * (1f - retreat)).coerceIn(0f, 1f))
            } else if (r.keyFill && exactKeyShape) {
                drawExactKey(canvas, r, style, keyAlpha.coerceIn(0f, 1f), retreat, elapsed)
            } else if (r.keyFill) {
                val spotSize = min(r.flashWidth, r.flashHeight) * 0.18f
                val width = r.flashWidth + (spotSize - r.flashWidth) * retreat
                val height = r.flashHeight + (spotSize - r.flashHeight) * retreat
                borderRect.set(-width / 2f, -height / 2f, width / 2f, height / 2f)
                val phase = retreat * (keyFlashTextures.size - 1)
                val lower = phase.toInt().coerceAtMost(keyFlashTextures.lastIndex - 1)
                val blend = phase - lower
                // Two cached masks interpolate the corners continuously, without
                // creating bitmaps, paths or shaders while typing.
                val firstAlpha = (keyAlpha * (1f - blend)).coerceIn(0f, 0.999f)
                style.flashPaint.alpha = (firstAlpha * 255f).toInt()
                canvas.drawBitmap(keyFlashTextures[lower], null, borderRect, style.flashPaint)
                style.flashPaint.alpha = (keyAlpha * blend / (1f - firstAlpha) * 255f).coerceIn(0f, 255f).toInt()
                canvas.drawBitmap(keyFlashTextures[lower + 1], null, borderRect, style.flashPaint)
            } else {
                // Functional keys keep a feathered round flash, never a solid rectangle.
                style.fallbackPaint.alpha = (keyAlpha.coerceIn(0f, 1f) * 255f).toInt()
                canvas.scale(r.flashWidth / 2f, r.flashHeight / 2f)
                canvas.drawCircle(0f, 0f, 1f, style.fallbackPaint)
            }
            canvas.restoreToCount(saved)
        }
    }

    private val borderRect = android.graphics.RectF()
    private val liquidRim = Path()

    /** the real rounded keycap in one colour, lit from behind; shrinks only for the shrink exit */
    private fun drawExactKey(canvas: Canvas, r: PressEffectTrail.Ripple, style: RipplePaints,
        alpha: Float, retreat: Float, elapsed: Long) {
        val spot = min(r.flashWidth, r.flashHeight) * 0.22f
        val w = r.flashWidth + (spot - r.flashWidth) * retreat
        val h = r.flashHeight + (spot - r.flashHeight) * retreat
        val radius = keyCornerRadius + (spot / 2f - keyCornerRadius) * retreat
        borderRect.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        // Every shape floats as one completely coloured cap. A ripple choice
        // must never turn its upper half into a dim "water level" or shadow.
        style.capPaint.alpha = (alpha * 255f).toInt()
        canvas.drawRoundRect(borderRect, radius, radius, style.capPaint)
        if (!lightLegends) {
            // A light theme uses dark lettering and can retain its subtle glint.
            val saved = canvas.save()
            canvas.translate(0f, -h * 0.14f)
            canvas.scale(w * 0.42f, h * 0.36f)
            style.highlightPaint.alpha = (alpha * 0.08f * 255f).toInt()
            canvas.drawCircle(0f, 0f, 1f, style.highlightPaint)
            canvas.restoreToCount(saved)
        }
        if (liquidKeyFaces) drawLiquidEdge(canvas, r, style, alpha, w, h, radius, elapsed)
    }

    /** A small same-colour glint stays at the rim; the cap interior remains uniform. */
    private fun drawLiquidEdge(canvas: Canvas, r: PressEffectTrail.Ripple, style: RipplePaints,
        alpha: Float, width: Float, height: Float, radius: Float, elapsed: Long) {
        val available = width - 2f * (radius + 2f * density)
        if (available < 6f * density) return
        val phase = elapsed.toFloat() / 380f + r.shape * 0.67f
        val length = available * 0.45f
        val left = -available / 2f + (available - length) * (0.5f + 0.5f * kotlin.math.sin(phase))
        val right = left + length
        val top = -height / 2f + 2f * density
        liquidRim.rewind()
        liquidRim.moveTo(left, top + 0.20f * density * kotlin.math.sin(phase))
        liquidRim.quadTo((left + right) / 2f, top - 0.25f * density,
            right, top + 0.20f * density * cos(phase * 0.8f))
        // Even the soft stroke stays within the upper four dp and clear of the
        // rounded corners. Nothing is filled below or drawn across the glyph.
        style.liquidRimPaint.strokeWidth = density * 3f
        style.liquidRimPaint.alpha = (alpha * 0.10f * 255f).toInt()
        canvas.drawPath(liquidRim, style.liquidRimPaint)
        style.liquidRimPaint.strokeWidth = density
        style.liquidRimPaint.alpha = (alpha * 0.35f * 255f).toInt()
        canvas.drawPath(liquidRim, style.liquidRimPaint)
    }

    /** neon outline around the pressed key: soft outer glow, brighter halo, crisp line */
    private fun drawBorderGlow(canvas: Canvas, r: PressEffectTrail.Ripple, style: RipplePaints, alpha: Float) {
        if (alpha <= 0.004f) return
        val paint = style.strokePaint
        for ((widthDp, layerAlpha) in BORDER_LAYERS) {
            val w = widthDp * density
            borderRect.set(-r.flashWidth / 2f + w / 2f, -r.flashHeight / 2f + w / 2f,
                r.flashWidth / 2f - w / 2f, r.flashHeight / 2f - w / 2f)
            paint.strokeWidth = w
            paint.alpha = (alpha * layerAlpha * 255f).coerceIn(0f, 255f).toInt()
            val radius = (keyCornerRadius - w / 2f).coerceAtLeast(0f)
            canvas.drawRoundRect(borderRect, radius, radius, paint)
        }
    }

    /** Invoked by the key face after its dark background, before its character. */
    /** Returns the current face brightness; the theme's legend colour stays fixed. */
    @Suppress("UNUSED_PARAMETER") // Inset arguments remain source-compatible with existing painters.
    fun drawKeySurface(canvas: Canvas, keyId: Int, width: Int, height: Int,
        horizontalInset: Int = 0, verticalInset: Int = 0): Float {
        if (!keySurfaceEffects || !canDraw() || !animatorsEnabled()) return 0f
        if (keyColorStyle == KEY_STYLE_OFF) return 0f
        val now = clock()
        val ripple = keyFaces.findKey(keyId) ?: return 0f
        if (!ripple.keyFill) return 0f
        val a = keyEnvelope(ripple, now)
        if (a <= 0f) return 0f
        val elapsed = (now - ripple.start).coerceAtLeast(0L)
        drawKeyFlash(canvas, ripple, styles[ripple.colorIndex], elapsed, 1f, width / 2f, height / 2f)
        return if (keyColorStyle != KEY_STYLE_BORDER && envelopeRetreat < 0.5f)
            a * styles[ripple.colorIndex].luminance else 0f
    }

    /** One continuous ease-out: a quick local response slows into a soft travelling tail. */
    private fun waveProgress(spread: Long): Float {
        val t = (spread.toFloat() / expansionDuration).coerceIn(0f, 1f)
        val remaining = 1f - t
        if (samGlare) return smoothstep(0f, 1f, t)
        return 1f - remaining * remaining * remaining
    }

    private fun freshLightWeight(r: PressEffectTrail.Ripple): Float {
        if (sharedFieldEnabled) {
            val spread = (frameTime - r.start - ignitionDuration).toFloat()
            // Expanded colour remains part of the same field; it does not lose
            // its energy before it reaches its configured propagation distance.
            return 1f - smoothstep(expansionDuration * 0.85f,
                expansionDuration + waveHoldDuration + fadeDuration * 0.25f, spread)
        }
        val phase = ((frameTime - r.start - ignitionDuration).toFloat() / expansionDuration).coerceIn(0f, 1f)
        // With 900ms this reproduces 250..650ms. Longer expansion settings stretch
        // the transition, and the fully expanded hold has a stable old-light level.
        return 1f - smoothstep(1f / 6f, 11f / 18f, phase)
    }

    private fun fadeEnvelope(r: PressEffectTrail.Ripple): Float =
        1f - smoothstep(fadeStart.toFloat(), waveDuration.toFloat(), (frameTime - r.start).toFloat())

    private fun waveAlpha(r: PressEffectTrail.Ripple): Float {
        val elapsed = (frameTime - r.start).coerceAtLeast(0L)
        if (samGlare) {
            if (elapsed >= waveDuration) return 0f
            val onset = 0.20f + 0.80f * smoothstep(0f, 32f, elapsed.toFloat())
            val end = fadeEnvelope(r)
            // Keep the selected fade duration perceptible, including its tail.
            // Squaring this envelope used to spend most of the tail nearly dark.
            return 1.35f * onset * end
        }
        val spread = elapsed - ignitionDuration
        if (spread < 0L || elapsed >= waveDuration) return 0f
        val progress = waveProgress(spread)
        // The face lights immediately; its surrounding field builds smoothly
        // across several frames instead of engulfing the margin in one frame.
        val ignition = smoothstep(0f, min(120f, expansionDuration * 0.20f), spread.toFloat())
        val end = fadeEnvelope(r)
        // Keep the outward front readable during expansion. Once its configured
        // fade begins, gradually unload overlapping sources before their common
        // exposure ceiling can hold them at a bright plateau. Mist keeps a more
        // visible quadratic tail; liquid retains its cubic exit. Both preserve
        // zero endpoint velocity and the exact configured lifetime, independently
        // of subsequent key presses and without increasing the peak exposure.
        return if (sharedFieldEnabled) {
            val tail = 0.80f * ignition * end * end
            if (rippleShape == org.fcitx.fcitx5.android.data.theme.ThemePrefs.RippleShape.SoftMist) tail
            else tail * end
        } else 0.80f * ignition * (1f - 0.35f * smoothstep(0.55f, 1f, progress)) * end
    }

    private fun bridgeAlpha(r: PressEffectTrail.Ripple): Float {
        val spread = (frameTime - r.start - ignitionDuration).coerceAtLeast(0L)
        val progress = waveProgress(spread)
        val expansionPhase = (spread.toFloat() / expansionDuration).coerceIn(0f, 1f)
        // Reach and lifetime follow the same shape and configured stages. Only the
        // far light's budget is independent of the newest key's local priority.
        val tail = (0.55f + 0.45f * (1f - smoothstep(0.35f, 1f, expansionPhase))) * fadeEnvelope(r)
        return waveAlpha(r) * 0.32f * smoothstep(0.06f, 0.32f, progress) * tail
    }

    private fun boundedLightScale(energy: Float, budget: Float): Float {
        val difference = budget - energy
        val shoulder = budget * 0.20f
        // This smooth maximum is always at least energy and budget. It rounds
        // off saturation's speed change while still respecting the hard cap.
        val denominator = (budget + energy + sqrt(difference * difference + shoulder * shoulder)) * 0.5f
        return budget / denominator
    }

    private fun draw(canvas: Canvas, strength: Float) {
        // The same envelope serves functional keys, including the release tail of a long hold.
        for (i in 0 until keyFaces.size) {
            val face = keyFaces[i]
            if (!samGlare && (!keySurfaceEffects || !face.keyFill || face.keyId < 0)) {
                drawKeyFlash(canvas, face, styles[face.colorIndex], (frameTime - face.start).coerceAtLeast(0L),
                    strength, face.x, face.y)
            }
        }
        if (sharedFieldEnabled) {
            drawNeonField(canvas, strength)
            return
        }
        // A long user-selected expansion may leave many waves in the fresh phase.
        // Recent visible waves get smooth priority, rather than splitting that
        // budget equally among all long tails. Both the sum and each numerator use
        // this priority; no extra storage or draw-time allocation is needed.
        var attention = 1f
        for (i in ripples.size - 1 downTo 0) {
            val r = ripples[i]
            freshAttention[i] = attention
            val fresh = freshLightWeight(r)
            val spread = (frameTime - r.start - ignitionDuration).toFloat()
            // New colour is already visible while local priority crosses over a
            // little more slowly. These weights still share the same hard budget.
            val onset = min((waveAlpha(r) / 0.80f).coerceIn(0f, 1f), smoothstep(0f, 180f, spread))
            attention *= 1f - 0.40f * fresh * onset
        }
        var freshEnergy = 0f
        var oldEnergy = 0f
        var bridgeEnergy = 0f
        for (i in 0 until ripples.size) {
            val r = ripples[i]
            val alpha = waveAlpha(r)
            val fresh = freshLightWeight(r)
            freshEnergy += alpha * fresh * freshAttention[i] * 1.32f
            oldEnergy += alpha * (1f - fresh) * 1.32f
            if (glowOnCandidates) bridgeEnergy += bridgeAlpha(r)
        }
        val freshScale = if (normalizeOldLight) boundedLightScale(freshEnergy, FRESH_LIGHT_BUDGET) else 1f
        val oldScale = if (normalizeOldLight) boundedLightScale(oldEnergy, OLD_LIGHT_BUDGET) else 1f
        val bridgeScale = if (normalizeOldLight && bridgeEnergy > BRIDGE_LIGHT_BUDGET)
            BRIDGE_LIGHT_BUDGET / bridgeEnergy else 1f
        val textures = Textures.ready
        for (i in 0 until ripples.size) {
            val r = ripples[i]
            val fresh = freshLightWeight(r)
            val proposedGain = fresh * freshAttention[i] * freshScale + (1f - fresh) * oldScale
            if (normalizeOldLight) r.lightGain = min(r.lightGain, proposedGain)
            if (normalizeOldLight) r.bridgeGain = min(r.bridgeGain, bridgeScale)
            val alpha = waveAlpha(r) * r.lightGain * strength * glowStrength
            val farAlpha = if (glowOnCandidates)
                bridgeAlpha(r) * r.bridgeGain * strength * glowStrength else 0f
            if (alpha <= 0.003f && farAlpha <= 0.002f) continue
            val spread = (frameTime - r.start - ignitionDuration).coerceAtLeast(0L)
            val progress = waveProgress(spread)
            val travel = maxRadius * r.size * progress
            // Concentrate the visible front near neighbouring keys and give that
            // smaller field more contrast. The weak far shoulder keeps its reach.
            val localTravel = travel * 0.68f
            val width = r.flashWidth + 2f * localTravel * r.stretchX
            val height = r.flashHeight + 2f * localTravel * r.stretchY
            val saved = canvas.save()
            canvas.translate(r.x + r.driftX * progress, r.y + r.driftY * progress)
            // Keep the new front vivid and let its older, broader local cloud retreat.
            // The weak, wider candidate bridge retains its independently fading strength.
            val localAlpha = alpha * (0.66f + 0.28f * fresh) * 1.20f
            // The first glow follows the real rounded key edge, without a circular wave front.
            val edgeAlpha = localAlpha * (1f - smoothstep(0.02f, 0.25f, progress))
            if (edgeAlpha > 0.003f) drawKeyEdgeMist(canvas, r, styles[r.colorIndex], edgeAlpha, travel)
            drawFluidLayer(canvas, textures, r, styles[r.colorIndex], localAlpha, width, height, progress)
            // A separately bounded weak shoulder can finish travelling to the
            // candidate strip even when typing has moved attention to a new key.
            // Stored gain only decreases, so stopping input never relights old fog.
            if (farAlpha > 0.002f) {
                drawFluidLayer(canvas, textures, r, styles[r.colorIndex], farAlpha,
                    r.flashWidth + 3.3f * travel * r.stretchX,
                    r.flashHeight + 3.3f * travel * r.stretchY, progress)
            }
            canvas.restoreToCount(saved)
        }
    }

    private fun drawNeonField(canvas: Canvas, strength: Float) {
        val field = neonField ?: return
        if (glowStrength <= 0f || host.width <= 0 || host.height <= 0) return
        if (fieldPreparedAt != frameTime || field.logicalWidth != host.width || field.logicalHeight != host.height) {
            field.prepare()
            fieldPreparedAt = frameTime
        }
        field.paint.alpha = (strength * glowStrength * 255f).coerceIn(0f, 255f).toInt()
        canvas.drawBitmap(field.bitmap, null, field.bounds, field.paint)
    }

    /**
     * One bounded RGB field replaces many independently dimmed light stamps.
     * Buffers and nodes are fixed: eight recent sources at 120x96, older sources
     * at 40x32, then one bilinear merge. Both grids describe the same light;
     * promoting a source to the older grid never turns it into a uniform box.
     */
    private inner class NeonField {
        private val red = FloatArray(FIELD_PIXELS)
        private val green = FloatArray(FIELD_PIXELS)
        private val blue = FloatArray(FIELD_PIXELS)
        private val weight = FloatArray(FIELD_PIXELS)
        // Sam separates visible light from colour mixing. Overlapping weak
        // shoulders must not accumulate into a uniformly bright keyboard.
        private val samExposure = if (samGlare) FloatArray(FIELD_PIXELS) else null
        private val pixels = IntArray(FIELD_PIXELS)
        private val oldRed = FloatArray(FIELD_OLD_PIXELS)
        private val oldGreen = FloatArray(FIELD_OLD_PIXELS)
        private val oldBlue = FloatArray(FIELD_OLD_PIXELS)
        private val oldWeight = FloatArray(FIELD_OLD_PIXELS)
        private val oldSamExposure = if (samGlare) FloatArray(FIELD_OLD_PIXELS) else null
        private val oldX = IntArray(FIELD_WIDTH)
        private val oldY = IntArray(FIELD_HEIGHT)
        private val oldMixX = FloatArray(FIELD_WIDTH)
        private val oldMixY = FloatArray(FIELD_HEIGHT)
        private val nodes = Array(FIELD_DIRECT_SOURCES + 1) { FieldNode() }
        val bitmap = Bitmap.createBitmap(FIELD_WIDTH, FIELD_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val bounds = android.graphics.RectF()
        var logicalWidth = 0
        var logicalHeight = 0
        var sourceCount = 0
            private set
        var visitedSamples = 0
            private set
        var mergedSamples = 0
            private set
        var prepareCount = 0
            private set

        init {
            for (x in 0 until FIELD_WIDTH) {
                val sample = ((x + 0.5f) * FIELD_OLD_WIDTH / FIELD_WIDTH - 0.5f)
                    .coerceIn(0f, (FIELD_OLD_WIDTH - 1).toFloat())
                oldX[x] = sample.toInt().coerceAtMost(FIELD_OLD_WIDTH - 2)
                oldMixX[x] = sample - oldX[x]
            }
            for (y in 0 until FIELD_HEIGHT) {
                val sample = ((y + 0.5f) * FIELD_OLD_HEIGHT / FIELD_HEIGHT - 0.5f)
                    .coerceIn(0f, (FIELD_OLD_HEIGHT - 1).toFloat())
                oldY[y] = sample.toInt().coerceAtMost(FIELD_OLD_HEIGHT - 2)
                oldMixY[y] = sample - oldY[y]
            }
        }

        fun prepare() {
            prepareCount++
            logicalWidth = host.width
            logicalHeight = host.height
            bounds.set(0f, -logicalHeight * 0.25f, logicalWidth.toFloat(), logicalHeight.toFloat())
            java.util.Arrays.fill(red, 0f)
            java.util.Arrays.fill(green, 0f)
            java.util.Arrays.fill(blue, 0f)
            java.util.Arrays.fill(weight, 0f)
            samExposure?.let { java.util.Arrays.fill(it, 0f) }
            sourceCount = 0
            visitedSamples = 0
            mergedSamples = 0
            val first = max(0, ripples.size - FIELD_DIRECT_SOURCES)
            val hasOlder = first > 0 && prepareOlder(first)
            for (i in first until ripples.size) {
                val ripple = ripples[i]
                val alpha = waveAlpha(ripple)
                if (alpha <= 0.001f) continue
                val node = nodes[sourceCount++]
                prepareNode(node, ripple, alpha)
            }
            val cellWidth = logicalWidth.toFloat() / FIELD_WIDTH
            val cellHeight = bounds.height() / FIELD_HEIGHT
            val directStart = if (hasOlder) 1 else 0
            for (i in directStart until sourceCount) accumulate(nodes[i], FIELD_WIDTH, FIELD_HEIGHT,
                cellWidth, cellHeight, red, green, blue, weight, samExposure)
            if (coordinatedNeon) {
                // Smooth hue numerators/weights before colour lookup. Otherwise
                // a narrow lime-to-cyan edge can still turn grey when Android
                // filters the final RGB bitmap. Reuse the two unused RGB buffers;
                // opacity/geometry are untouched, and this is one fixed 3x3 kernel.
                for (y in 0 until FIELD_HEIGHT) for (x in 0 until FIELD_WIDTH) {
                    val i = y * FIELD_WIDTH + x
                    val left = if (x > 0) i - 1 else i
                    val right = if (x + 1 < FIELD_WIDTH) i + 1 else i
                    green[i] = red[left] + 2f * red[i] + red[right]
                    blue[i] = weight[left] + 2f * weight[i] + weight[right]
                }
            }
            for (y in 0 until FIELD_HEIGHT) {
                val logicalY = bounds.top + (y + 0.5f) * cellHeight
                // The candidate strip receives only a faint continuation of the
                // same field. Start attenuation inside the keyboard so its seam
                // cannot become a hard horizontal edge during rapid input.
                val candidate = if (glowOnCandidates) {
                    0.13f + 0.87f * smoothstep(0f, 32f * density, logicalY)
                } else if (logicalY < 0f) 0f else 1f
                for (x in 0 until FIELD_WIDTH) {
                    val index = y * FIELD_WIDTH + x
                    val w = weight[index]
                    if (w <= (if (samGlare) 0.000001f else 0.001f) || candidate <= 0f) {
                        pixels[index] = 0
                        continue
                    }
                    var r = red[index] / w
                    var g = green[index] / w
                    var b = blue[index] / w
                    if (coordinatedNeon) {
                        val above = if (y > 0) index - FIELD_WIDTH else index
                        val below = if (y + 1 < FIELD_HEIGHT) index + FIELD_WIDTH else index
                        val hueWeight = blue[above] + 2f * blue[index] + blue[below]
                        r = (green[above] + 2f * green[index] + green[below]) / hueWeight
                        // Interpolate an unwrapped hue path, not complementary RGB.
                        // Neighbouring output cells traverse adjacent neon tones;
                        // filtering cannot cross a hard dominant-colour fallback.
                        val position = if (cyclicNeon) ((r % colors.size) + colors.size) % colors.size
                            else r.coerceIn(0f, colors.lastIndex.toFloat())
                        val first = position.toInt()
                        val mix = position - first
                        val from = colors[first]
                        val to = colors[if (cyclicNeon) (first + 1) % colors.size else min(first + 1, colors.lastIndex)]
                        r = (Color.red(from) + (Color.red(to) - Color.red(from)) * mix) / 255f
                        g = (Color.green(from) + (Color.green(to) - Color.green(from)) * mix) / 255f
                        b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * mix) / 255f
                        if (!samGlare) {
                            val high = max(r, max(g, b))
                            val low = min(r, min(g, b))
                            val chroma = (high - low).coerceAtLeast(0.001f)
                            // The original two modes keep their pure neon path.
                            // Sam retains the palette's own RGB/chroma so its
                            // weak light does not become a harsh coloured grid.
                            r = (r - low) / chroma
                            g = (g - low) / chroma
                            b = (b - low) / chroma
                        }
                    }
                    // A spatial exposure ceiling is independent of source count:
                    // a new key cannot globally divide the already travelling front.
                    val alpha = if (samGlare) samExposure!![index] * candidate
                        else 0.88f * w / (0.55f + w) * candidate
                    pixels[index] = ((alpha * 255f).toInt() shl 24) or
                        ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
                        ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
                        (b * 255f).toInt().coerceIn(0, 255)
                }
            }
            bitmap.setPixels(pixels, 0, FIELD_WIDTH, 0, 0, FIELD_WIDTH, FIELD_HEIGHT)
        }

        private fun prepareNode(node: FieldNode, ripple: PressEffectTrail.Ripple, alpha: Float) {
            val spread = (frameTime - ripple.start - ignitionDuration).coerceAtLeast(0L)
            val progress = waveProgress(spread)
            if (samGlare) {
                prepareSamNode(node, ripple, alpha, progress)
                return
            }
            // Letter pitch, rather than total keyboard height, defines reach.
            // Removing the number row must not change how many neighbours glow.
            // Wide function keys retain their own immediate face, but do not
            // launch a keyboard-wide strong cloud merely because they are wide.
            val pitch = logicalWidth / 10f
            val travel = pitch * 2.85f * sizeScale * glowReach * ripple.size * progress
            val motion = smoothstep(0.40f, 1f, progress)
            // This clock continues through hold/fade. Only the light moves a few
            // pixels; cap geometry and the character's anchor never move.
            val phase = (frameTime - ripple.start).toFloat() / 460f + ripple.shape * 0.83f
            node.x = ripple.x + ripple.driftX * progress * 0.25f + kotlin.math.sin(phase) * density * 1.4f * motion
            node.y = ripple.y + ripple.driftY * progress * 0.25f + cos(phase * 0.79f) * density * 1.2f * motion
            node.halfWidth = (ripple.flashWidth / 2f + travel * ripple.stretchX) *
                (1f + kotlin.math.sin(phase * 0.63f) * 0.015f * motion)
            val candidateTravel = if (glowOnCandidates)
                (ripple.y + logicalHeight * 0.25f - ripple.flashHeight / 2f).coerceAtLeast(0f) * progress else 0f
            node.halfHeight = (ripple.flashHeight / 2f + max(travel * ripple.stretchY, candidateTravel)) *
                (1f + cos(phase * 0.71f) * 0.015f * motion)
            // The outer deformed front stays soft and connected. Its bright
            // core is independent of the taller footprint reaching candidates.
            // A modestly narrower bright core leaves each key's release legible.
            node.coreHalfWidth = min(ripple.flashWidth / 2f, pitch / 2f) +
                pitch * 1.05f * sizeScale * glowReach * ripple.size * progress
            node.coreHalfHeight = min(ripple.flashHeight / 2f, pitch * 0.6f) +
                pitch * 1.15f * sizeScale * glowReach * ripple.size * progress
            // The weak candidate bridge must retain its original density as
            // well as candidateTravel's outer reach. A compact keyboard core
            // alone can reach the strip geometrically but leave it invisible.
            node.candidateCoreHalfWidth = min(ripple.flashWidth / 2f, pitch / 2f) +
                pitch * 1.15f * sizeScale * glowReach * ripple.size * progress
            node.candidateCoreHalfHeight = min(ripple.flashHeight / 2f, pitch * 0.6f) +
                pitch * 1.25f * sizeScale * glowReach * ripple.size * progress
            node.corner = min(node.halfWidth, min(node.halfHeight, keyCornerRadius + travel * 0.20f))
            val fluidity = smoothstep(0.12f, 0.70f, progress)
            // Keep the first key-edge response precise. Once the front has left
            // that edge, its sides bend independently and gain a wider shoulder.
            node.feather = (1.8f * density + travel * 0.025f).coerceAtMost(10f * density) +
                min(travel * 0.18f, 24f * density) * fluidity
            // The mid-side bend must remain visible after removing translation;
            // this changes the front's shape, not its reach or exposure.
            node.bendX = min(travel * 0.15f, 22f * density) * fluidity
            node.bendY = min(travel * 0.12f, 18f * density) * fluidity
            node.curveX = kotlin.math.sin(phase * 0.93f)
            node.skewX = cos(phase * 0.93f + 0.6f)
            node.curveY = cos(phase * 0.87f + 0.8f)
            node.skewY = kotlin.math.sin(phase * 0.87f + 1.4f)
            node.fluidMix = if (rippleShape == org.fcitx.fcitx5.android.data.theme.ThemePrefs.RippleShape.IrregularFluid)
                smoothstep(0.06f, 0.65f, progress) else 0f
            if (node.fluidMix > 0f) {
                // Each source keeps a stable phase and evolves through hold;
                // no per-frame random sampling or texture replacement.
                val fluidPhase = (frameTime - ripple.start).toFloat() / 280f + ripple.shape * 0.83f
                node.fluidCos2 = cos(fluidPhase * 0.71f + 0.4f)
                node.fluidSin2 = kotlin.math.sin(fluidPhase * 0.71f + 0.4f)
                node.fluidCos3 = cos(fluidPhase)
                node.fluidSin3 = kotlin.math.sin(fluidPhase)
                node.fluidCos5 = cos(fluidPhase * 0.47f + 1.1f)
                node.fluidSin5 = kotlin.math.sin(fluidPhase * 0.47f + 1.1f)
                // Connected liquid pools move inside the compact bright core.
                // A weak change only at the far mist edge is mostly hidden by
                // opaque keycaps; these lobes move the visible light in the gaps.
                node.fluidPoolX = 0.31f * node.fluidCos2
                node.fluidPoolY = 0.26f * node.fluidSin2
                node.fluidBudX = -0.40f * node.fluidCos2 + 0.13f * node.fluidCos3
                node.fluidBudY = -0.35f * node.fluidSin2 + 0.13f * node.fluidSin3
                node.fluidPoolInverseRadius = 1f / (0.66f + 0.055f * node.fluidSin3)
                node.fluidBudInverseRadius = 1f / (0.47f + 0.06f * node.fluidCos5)
            }
            node.weight = alpha
            val color = colors[ripple.colorIndex]
            // Reuse the fixed red buffer for weighted hue positions in the preset.
            // Arbitrary user palettes retain their original RGB interpolation.
            node.red = if (coordinatedNeon) ripple.colorPosition else Color.red(color) / 255f
            node.green = if (coordinatedNeon) 0f else Color.green(color) / 255f
            node.blue = if (coordinatedNeon) 0f else Color.blue(color) / 255f
        }

        private fun prepareSamNode(node: FieldNode, ripple: PressEffectTrail.Ripple, alpha: Float, progress: Float) {
            val pitch = logicalWidth / 10f
            val reach = sqrt(sizeScale * glowReach)
            val travel = pitch * 5.1f * reach * ripple.size * progress
            val phase = (frameTime - ripple.start).toFloat() / 820f + ripple.shape * 0.83f
            node.x = ripple.x + ripple.driftX * progress * 0.18f
            node.y = ripple.y + ripple.driftY * progress * 0.18f
            // A broad band advances between black caps and leaves connected
            // colour behind it. Keep its later travel visible: a narrow, weakening
            // crest reads as a faint outline instead of the reference's moving wash.
            node.halfWidth = min(ripple.flashWidth / 2f, pitch / 2f) + travel
            node.halfHeight = min(ripple.flashHeight / 2f, pitch * 0.6f) + travel * 0.76f
            // Fast broad reach belongs to the dim shoulder. The much smaller
            // bright core remains attached to this particular touch location.
            node.coreHalfWidth = min(ripple.flashWidth / 2f, pitch / 2f) +
                pitch * 0.90f * reach * ripple.size * progress
            node.coreHalfHeight = min(ripple.flashHeight / 2f, pitch * 0.6f) +
                pitch * 0.88f * reach * ripple.size * progress
            node.candidateCoreHalfWidth = node.coreHalfWidth
            node.candidateCoreHalfHeight = node.coreHalfHeight
            node.corner = min(node.halfWidth, node.halfHeight)
            node.feather = 5f * density + travel * 0.18f
            node.samFrontWidth = pitch * 0.85f + travel * 0.26f
            node.samFrontStrength = 0.62f * (1f - 0.18f * smoothstep(0.65f, 1f, progress))
            // The wake clears from the source as the band leaves. Key-face
            // colour has its own release clock and must not hold this centre lit.
            node.samWakeStrength = 1f - 0.94f * smoothstep(0.40f, 1f, progress)
            node.bendX = travel * 0.018f
            node.bendY = travel * 0.014f
            node.curveX = kotlin.math.sin(phase)
            node.skewX = cos(phase * 0.73f)
            node.curveY = cos(phase * 0.81f)
            node.skewY = kotlin.math.sin(phase * 0.67f)
            node.fluidMix = 0f
            node.weight = alpha
            val color = colors[ripple.colorIndex]
            node.red = if (coordinatedNeon) ripple.colorPosition else Color.red(color) / 255f
            node.green = if (coordinatedNeon) 0f else Color.green(color) / 255f
            node.blue = if (coordinatedNeon) 0f else Color.blue(color) / 255f
        }

        private fun prepareOlder(end: Int): Boolean {
            val scratch = nodes[FIELD_DIRECT_SOURCES]
            java.util.Arrays.fill(oldRed, 0f)
            java.util.Arrays.fill(oldGreen, 0f)
            java.util.Arrays.fill(oldBlue, 0f)
            java.util.Arrays.fill(oldWeight, 0f)
            oldSamExposure?.let { java.util.Arrays.fill(it, 0f) }
            var any = false
            for (i in 0 until end) {
                val ripple = ripples[i]
                val alpha = waveAlpha(ripple)
                if (alpha <= 0.001f) continue
                prepareNode(scratch, ripple, alpha)
                accumulate(scratch, FIELD_OLD_WIDTH, FIELD_OLD_HEIGHT,
                    logicalWidth.toFloat() / FIELD_OLD_WIDTH, bounds.height() / FIELD_OLD_HEIGHT,
                    oldRed, oldGreen, oldBlue, oldWeight, oldSamExposure)
                any = true
            }
            if (!any) return false
            sourceCount++ // the old grid is one bounded composite, not a tenth direct source
            for (y in 0 until FIELD_HEIGHT) for (x in 0 until FIELD_WIDTH) {
                mergedSamples++
                val a = oldY[y] * FIELD_OLD_WIDTH + oldX[x]
                val b = a + 1
                val c = a + FIELD_OLD_WIDTH
                val d = c + 1
                val fx = oldMixX[x]
                val fy = oldMixY[y]
                val index = y * FIELD_WIDTH + x
                weight[index] = bilinear(oldWeight, a, b, c, d, fx, fy)
                red[index] = bilinear(oldRed, a, b, c, d, fx, fy)
                green[index] = bilinear(oldGreen, a, b, c, d, fx, fy)
                blue[index] = bilinear(oldBlue, a, b, c, d, fx, fy)
                if (samGlare) samExposure!![index] = bilinear(oldSamExposure!!, a, b, c, d, fx, fy)
            }
            return true
        }

        private fun bilinear(values: FloatArray, a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float): Float {
            val top = values[a] + (values[b] - values[a]) * fx
            val bottom = values[c] + (values[d] - values[c]) * fx
            return top + (bottom - top) * fy
        }

        private fun accumulate(node: FieldNode, gridWidth: Int, gridHeight: Int, cellWidth: Float, cellHeight: Float,
            rBuffer: FloatArray, gBuffer: FloatArray, bBuffer: FloatArray, wBuffer: FloatArray,
            exposureBuffer: FloatArray?) {
            val fluidMarginX = node.halfWidth * 0.18f * node.fluidMix
            val fluidMarginY = node.halfHeight * 0.18f * node.fluidMix
            val inverseHalfWidth = if (node.fluidMix > 0f) 1f / node.halfWidth else 0f
            val inverseHalfHeight = if (node.fluidMix > 0f) 1f / node.halfHeight else 0f
            val fluidDistanceScale = min(node.halfWidth, node.halfHeight)
            val firstX = ((node.x - node.halfWidth - node.bendX * 1.3f - node.feather - fluidMarginX) / cellWidth).toInt().coerceIn(0, gridWidth - 1)
            val lastX = ((node.x + node.halfWidth + node.bendX * 1.3f + node.feather + fluidMarginX) / cellWidth).toInt().coerceIn(0, gridWidth - 1)
            val firstY = ((node.y - node.halfHeight - node.bendY * 1.3f - node.feather - fluidMarginY - bounds.top) / cellHeight).toInt().coerceIn(0, gridHeight - 1)
            val lastY = ((node.y + node.halfHeight + node.bendY * 1.3f + node.feather + fluidMarginY - bounds.top) / cellHeight).toInt().coerceIn(0, gridHeight - 1)
            for (x in firstX..lastX) {
                val offset = (x + 0.5f) * cellWidth - node.x
                val t = (offset / node.halfWidth).coerceIn(-1f, 1f)
                val square = t * t
                node.dx[x] = offset
                node.columnBend[x] = node.bendY * ((1f - square) *
                    (node.curveY * (1f - 1.5f * square) + node.skewY * t))
                val coreOffset = offset / node.coreHalfWidth.coerceAtLeast(1f)
                val coreSquare = coreOffset * coreOffset
                node.columnDensity[x] = 1f / (1f + coreSquare * coreSquare)
                val candidateOffset = offset / node.candidateCoreHalfWidth.coerceAtLeast(1f)
                val candidateSquare = candidateOffset * candidateOffset
                node.candidateColumnDensity[x] = 1f / (1f + candidateSquare * candidateSquare)
            }
            for (y in firstY..lastY) {
                val logicalY = bounds.top + (y + 0.5f) * cellHeight
                val offset = logicalY - node.y
                val t = (offset / node.halfHeight).coerceIn(-1f, 1f)
                val square = t * t
                node.dy[y] = offset
                node.rowBend[y] = node.bendX * ((1f - square) *
                    (node.curveX * (1f - 1.5f * square) + node.skewX * t))
                val coreOffset = offset / node.coreHalfHeight.coerceAtLeast(1f)
                val coreSquare = coreOffset * coreOffset
                node.rowDensity[y] = 1f / (1f + coreSquare * coreSquare)
                val candidateOffset = offset / node.candidateCoreHalfHeight.coerceAtLeast(1f)
                val candidateSquare = candidateOffset * candidateOffset
                node.candidateRowDensity[y] = 1f / (1f + candidateSquare * candidateSquare)
                // Match the existing candidate attenuation's smooth seam. Below
                // this top transition the compact keyboard field is unchanged.
                val bridge = if (glowOnCandidates) 1f - smoothstep(0f, 32f * density, logicalY) else 0f
                node.candidateMix[y] = bridge
                if (node.fluidMix > 0f) {
                    node.inverseCoreWidth[y] = 1f / (node.coreHalfWidth +
                        (node.candidateCoreHalfWidth - node.coreHalfWidth) * bridge)
                    node.inverseCoreHeight[y] = 1f / (node.coreHalfHeight +
                        (node.candidateCoreHalfHeight - node.coreHalfHeight) * bridge)
                }
            }
            for (y in firstY..lastY) for (x in firstX..lastX) {
                visitedSamples++
                // The readable candidate strip retains its faint mist bridge.
                // Only the keyboard body becomes liquid, with a smooth top seam.
                val fluidBlend = node.fluidMix * (1f - node.candidateMix[y])
                val dx = abs(node.dx[x] - node.rowBend[y]) - node.halfWidth + node.corner
                val dy = abs(node.dy[y] - node.columnBend[x]) - node.halfHeight + node.corner
                var distance = if (samGlare) {
                    val u = (node.dx[x] - node.rowBend[y]) / node.halfWidth
                    val v = (node.dy[y] - node.columnBend[x]) / node.halfHeight
                    (sqrt(u * u + v * v) - 1f) * min(node.halfWidth, node.halfHeight)
                } else if (fluidBlend == 1f) 0f else
                    (if (dx > 0f && dy > 0f) sqrt(dx * dx + dy * dy) else max(dx, dy)) - node.corner
                var fluidDensity = 0f
                if (fluidBlend > 0f) {
                    val horizontal = node.dx[x] - node.rowBend[y]
                    val vertical = node.dy[y] - node.columnBend[x]
                    val u = horizontal * inverseHalfWidth
                    val v = vertical * inverseHalfHeight
                    val radius = sqrt(u * u + v * v)
                    val inverseRadius = if (radius > 0.0001f) 1f / radius else 0f
                    val unitX = if (inverseRadius > 0f) u * inverseRadius else 1f
                    val unitY = v * inverseRadius
                    // Angular harmonics evaluated algebraically: two broad
                    // shoulders, three lobes and one smaller uneven edge.
                    // No trigonometry or allocation in the sample loop.
                    val cos2 = unitX * unitX - unitY * unitY
                    val sin2 = 2f * unitX * unitY
                    val cos3 = cos2 * unitX - sin2 * unitY
                    val sin3 = sin2 * unitX + cos2 * unitY
                    val cos5 = cos3 * cos2 - sin3 * sin2
                    val sin5 = sin3 * cos2 + cos3 * sin2
                    // Deeper connected waists distinguish liquid from a rounded
                    // cloud. Maximum radius 1.16 remains below the old 1.18.
                    val contour = 0.80f + 0.18f * (cos3 * node.fluidCos3 + sin3 * node.fluidSin3) +
                        0.12f * (cos2 * node.fluidCos2 + sin2 * node.fluidSin2) +
                        0.06f * (cos5 * node.fluidCos5 + sin5 * node.fluidSin5)
                    val fluidDistance = (radius - contour) * fluidDistanceScale
                    distance += (fluidDistance - distance) * fluidBlend
                    val coreX = horizontal * node.inverseCoreWidth[y]
                    val coreY = vertical * node.inverseCoreHeight[y]
                    val mainX = (coreX - node.fluidPoolX) * node.fluidPoolInverseRadius
                    val mainY = (coreY - node.fluidPoolY) * node.fluidPoolInverseRadius
                    val budX = (coreX - node.fluidBudX) * node.fluidBudInverseRadius
                    val budY = (coreY - node.fluidBudY) * node.fluidBudInverseRadius
                    val mainDistance = mainX * mainX + mainY * mainY
                    val budDistance = budX * budX + budY * budY
                    val pool = 1f / (1f + mainDistance * mainDistance * mainDistance)
                    val bud = 1f / (1f + budDistance * budDistance * budDistance)
                    // Smooth union, bounded 0..1: no hard maximum, detach/pop,
                    // global gain change or extra exposure while pools merge.
                    val organic = pool + bud - pool * bud
                    val compact = node.columnDensity[x] * node.rowDensity[y]
                    val bridge = node.candidateColumnDensity[x] * node.candidateRowDensity[y]
                    val original = compact + (bridge - compact) * node.candidateMix[y]
                    fluidDensity = original + (organic - original) * fluidBlend
                }
                // Liquid retains a soft boundary, but not the broad mist blur
                // that erased its contour behind narrow keyboard gaps.
                val feather = node.feather * (1f - 0.45f * fluidBlend)
                val coverage = 1f - smoothstep(-feather * 0.20f, feather, distance)
                if (coverage <= 0f) continue
                val index = y * gridWidth + x
                val w = if (samGlare) {
                    val horizontal = (node.dx[x] - node.rowBend[y]) / node.coreHalfWidth
                    val vertical = (node.dy[y] - node.columnBend[x]) / node.coreHalfHeight
                    val coreRadius = horizontal * horizontal + vertical * vertical
                    val coreDenominator = 1f + coreRadius
                    val core = 0.68f / (coreDenominator * coreDenominator)
                    val outerX = (node.dx[x] - node.rowBend[y]) / node.halfWidth
                    val outerY = (node.dy[y] - node.columnBend[x]) / node.halfHeight
                    val outerRadius = sqrt(outerX * outerX + outerY * outerY)
                    val shoulder = 0.13f / (1f + 2f * outerRadius * outerRadius)
                    val distanceFromFront = abs(outerRadius - 0.78f) * min(node.halfWidth, node.halfHeight)
                    val front = node.samFrontStrength *
                        (1f - smoothstep(0f, node.samFrontWidth, distanceFromFront))
                    val wake = (core + shoulder - core * shoulder) * node.samWakeStrength
                    val exposure = (node.weight / 1.35f) * coverage * (wake + front - wake * front)
                    // The strongest local source determines exposure; another
                    // distant shoulder cannot lift the whole scene to its peak.
                    // max is continuous as sources expand and fade, so their
                    // boundaries join without a new-wave reset or a hard ring.
                    exposureBuffer!![index] = max(exposureBuffer[index], exposure)
                    // Prefer the actual local colour pool over the numerous
                    // faint far tails. Old sources retain their own colours.
                    exposure * exposure
                } else if (fluidBlend > 0f) node.weight * coverage * fluidDensity
                    else if (node.candidateMix[y] > 0f) {
                        val compact = node.columnDensity[x] * node.rowDensity[y]
                        val bridge = node.candidateColumnDensity[x] * node.candidateRowDensity[y]
                        node.weight * coverage * (compact + (bridge - compact) * node.candidateMix[y])
                    } else node.weight * coverage * node.columnDensity[x] * node.rowDensity[y]
                wBuffer[index] += w
                rBuffer[index] += w * node.red
                gBuffer[index] += w * node.green
                bBuffer[index] += w * node.blue
            }
        }
    }

    private class FieldNode {
        var x = 0f
        var y = 0f
        var halfWidth = 0f
        var halfHeight = 0f
        var coreHalfWidth = 0f
        var coreHalfHeight = 0f
        var candidateCoreHalfWidth = 0f
        var candidateCoreHalfHeight = 0f
        var corner = 0f
        var feather = 0f
        var weight = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        var bendX = 0f
        var bendY = 0f
        var curveX = 0f
        var curveY = 0f
        var skewX = 0f
        var skewY = 0f
        var fluidMix = 0f
        var samFrontWidth = 0f
        var samFrontStrength = 0f
        var samWakeStrength = 1f
        var fluidCos2 = 0f
        var fluidSin2 = 0f
        var fluidCos3 = 0f
        var fluidSin3 = 0f
        var fluidCos5 = 0f
        var fluidSin5 = 0f
        var fluidPoolX = 0f
        var fluidPoolY = 0f
        var fluidBudX = 0f
        var fluidBudY = 0f
        var fluidPoolInverseRadius = 1f
        var fluidBudInverseRadius = 1f
        val dx = FloatArray(FIELD_WIDTH)
        val dy = FloatArray(FIELD_HEIGHT)
        val columnBend = FloatArray(FIELD_WIDTH)
        val rowBend = FloatArray(FIELD_HEIGHT)
        val columnDensity = FloatArray(FIELD_WIDTH)
        val rowDensity = FloatArray(FIELD_HEIGHT)
        val candidateColumnDensity = FloatArray(FIELD_WIDTH)
        val candidateRowDensity = FloatArray(FIELD_HEIGHT)
        val candidateMix = FloatArray(FIELD_HEIGHT)
        val inverseCoreWidth = FloatArray(FIELD_HEIGHT)
        val inverseCoreHeight = FloatArray(FIELD_HEIGHT)
    }

    private fun drawFluidLayer(canvas: Canvas, textures: Textures?, r: PressEffectTrail.Ripple,
        style: RipplePaints, alpha: Float, width: Float, height: Float, progress: Float) {
        if (textures == null) {
            drawFallbackMist(canvas, r, style, alpha, width, height, progress)
            return
        }
        val saved = canvas.save()
        val phase = progress * (FLUID_PHASES - 1)
        val lower = phase.toInt().coerceAtMost(FLUID_PHASES - 2)
        val blend = phase - lower
        // Geometry is expressed entirely in keyboard pixels. An explicit target rect
        // also makes drawBitmap immune to a cached mask's display-density metadata.
        borderRect.set(-width / 2f, -height / 2f, width / 2f, height / 2f)
        // Compensate the second SRC_OVER phase so a bright centre does not dip
        // every time the cached masks cross-fade through their midpoint.
        val firstAlpha = (alpha * (1f - blend)).coerceIn(0f, 0.999f)
        style.paint.alpha = (firstAlpha * 255f).toInt()
        canvas.drawBitmap(textures.fluid[r.shape][lower], null, borderRect, style.paint)
        style.paint.alpha = (alpha * blend / (1f - firstAlpha) * 255f).coerceIn(0f, 255f).toInt()
        canvas.drawBitmap(textures.fluid[r.shape][lower + 1], null, borderRect, style.paint)
        canvas.restoreToCount(saved)
    }

    /** Cached paint, bounded layers, and real key radii: no draw-time masks or paths. */
    private fun drawKeyEdgeMist(canvas: Canvas, r: PressEffectTrail.Ripple, style: RipplePaints, alpha: Float, travel: Float) {
        val outward = 1.5f * density + min(travel, 12f * density)
        for (i in 6 downTo 1) {
            val feather = outward * i / 6f
            borderRect.set(-r.flashWidth / 2f - feather, -r.flashHeight / 2f - feather,
                r.flashWidth / 2f + feather, r.flashHeight / 2f + feather)
            style.solidPaint.alpha = (alpha * (0.035f + (7 - i) * 0.013f) * 255f).toInt()
            canvas.drawRoundRect(borderRect, keyCornerRadius + feather, keyCornerRadius + feather, style.solidPaint)
        }
    }

    /** Also feathered and asymmetric during one-time background texture preparation. */
    private fun drawFallbackMist(canvas: Canvas, r: PressEffectTrail.Ripple, style: RipplePaints,
        alpha: Float, width: Float, height: Float, progress: Float) {
        for (i in 5 downTo 1) {
            val k = 0.55f + i * 0.09f
            val dx = r.driftX * progress * (i - 3) * 0.2f
            val dy = r.driftY * progress * (3 - i) * 0.2f
            borderRect.set(-width * k / 2f + dx, -height * k / 2f + dy,
                width * k / 2f + dx, height * k / 2f + dy)
            style.solidPaint.alpha = (alpha * 0.10f * 255f).toInt()
            val radius = keyCornerRadius + min(width, height) * progress * 0.28f
            canvas.drawRoundRect(borderRect, radius, radius, style.solidPaint)
        }
    }

    private fun animatorsEnabled(): Boolean = animationsAllowed()

    /** Cached shape phases morph an exact-ish key footprint into an uneven, feathered cloud. */
    private class Textures(val fluid: Array<Array<Bitmap>>) {
        companion object {
            @Volatile var ready: Textures? = null
                private set
            @Volatile private var started = false

            fun ensure() {
                if (ready != null || started) return
                synchronized(this) {
                    if (ready != null || started) return
                    started = true
                }
                Thread({
                    try { prepare() } catch (e: Exception) { started = false }
                }, "PressEffectTextures").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }.start()
            }

            @Synchronized fun prepare() {
                if (ready != null) return
                val noise = PressEffectMasks.Noise(20261004)
                val fluid = Array(PressEffectMasks.SHAPES) { shape ->
                    Array(FLUID_PHASES) { phase ->
                        val pixels = PressEffectMasks.fluid(noise, PressEffectMasks.bodySeed(shape), phase.toFloat() / (FLUID_PHASES - 1), FLUID_TEXTURE_SIZE)
                        Bitmap.createBitmap(pixels, FLUID_TEXTURE_SIZE, FLUID_TEXTURE_SIZE, Bitmap.Config.ARGB_8888).apply {
                            density = Bitmap.DENSITY_NONE
                            prepareToDraw()
                        }
                    }
                }
                ready = Textures(fluid)
            }
        }
    }

    /** Legacy mask renderer test preparation; the common field needs no mask cache. */
    internal fun prepareTexturesForTest() { if (!sharedFieldEnabled) Textures.prepare() }

    /** Exercise metadata changes independently of the explicitly sized cached masks. */
    internal fun setTextureDensityForTest(value: Int) {
        val textures = Textures.ready
        if (textures != null) for (shape in textures.fluid) for (mask in shape) mask.density = value
        for (mask in keyFlashTextures) mask.density = value
        neonField?.bitmap?.density = value
    }

    companion object {
        private val UPTIME_CLOCK: () -> Long = SystemClock::uptimeMillis
        /** colour chosen by the most recent key press (0 = none yet); read by the character bubble */
        @Volatile
        var lastPressColor: Int = 0
        private val keyColors = android.util.SparseIntArray()
        private val keyColorTimes = android.util.SparseLongArray()
        fun colorForKey(keyId: Int): Int? {
            val i = keyColors.indexOfKey(keyId)
            if (i < 0) return null
            val age = SystemClock.uptimeMillis() - keyColorTimes.get(keyId)
            if (age !in 0L..2000L) {
                forgetKeyColor(keyId)
                return null
            }
            return keyColors.valueAt(i)
        }
        private fun forgetKeyColor(keyId: Int) {
            if (keyId < 0) return
            keyColors.delete(keyId)
            keyColorTimes.delete(keyId)
        }
        const val KEY_STYLE_FILL = 0
        const val KEY_STYLE_BORDER = 1
        const val KEY_STYLE_OFF = 2
        private val BORDER_LAYERS = arrayOf(7f to 0.16f, 4f to 0.32f, 1.8f to 1f)
        private const val BASE_RADIUS_DP = 200f
        private const val FLASH_RADIUS_DP = 19f
        private const val KEY_FLASH_SIZE = 64
        private val KEY_FLASH_TEXTURES by lazy {
            Array(9) { phase ->
                Bitmap.createBitmap(PressEffectMasks.keycap(KEY_FLASH_SIZE, phase / 8f), KEY_FLASH_SIZE,
                    KEY_FLASH_SIZE, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                    prepareToDraw()
                }
            }
        }
        private const val MAX_RIPPLES = 32
        private const val MAX_KEY_FACES = 96
        private const val FIELD_WIDTH = 120
        private const val FIELD_HEIGHT = 96
        private const val FIELD_PIXELS = FIELD_WIDTH * FIELD_HEIGHT
        private const val FIELD_DIRECT_SOURCES = 8
        private const val FIELD_OLD_WIDTH = 40
        private const val FIELD_OLD_HEIGHT = 32
        private const val FIELD_OLD_PIXELS = FIELD_OLD_WIDTH * FIELD_OLD_HEIGHT
        private const val FRESH_LIGHT_BUDGET = 1.50f
        private const val OLD_LIGHT_BUDGET = 0.30f
        private const val BRIDGE_LIGHT_BUDGET = 0.40f
        private const val FLUID_PHASES = 9
        private const val FLUID_TEXTURE_SIZE = 128

        /** Public aliases retained for existing themes, tests and integrations. */
        val SAMSUNG_COOL = PressColorPalette.CYAN_BLUE_PURPLE
        val CYBERPUNK = PressColorPalette.CYBERPUNK
        val COOL = PressColorPalette.COOL
        val RAINBOW = PressColorPalette.RAINBOW
        val BERRY = PressColorPalette.BERRY

        private fun ramp(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

        internal fun smoothstep(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** on light keyboards neon pastels wash out: deepen them a little */
        private fun adapt(color: Int, isDark: Boolean): Int {
            if (isDark) return color or 0xFF000000.toInt()
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = min(1f, hsv[1] * 1.1f)
            hsv[2] = hsv[2] * 0.86f
            return Color.HSVToColor(hsv)
        }

        /** Same hue across the entire cap; sufficient contrast without a glyph outline. */
        private fun readableCapColor(color: Int): Int {
            fun linear(channel: Float): Float = if (channel <= 0.04045f) channel / 12.92f
                else ((channel + 0.055f) / 1.055f).pow(2.4f)
            val red = Color.red(color) / 255f
            val green = Color.green(color) / 255f
            val blue = Color.blue(color) / 255f
            fun luminance(scale: Float) = 0.2126f * linear(red * scale) +
                0.7152f * linear(green * scale) + 0.0722f * linear(blue * scale)
            if (luminance(1f) <= 0.18f) return color
            var low = 0f
            var high = 1f
            repeat(12) {
                val middle = (low + high) / 2f
                if (luminance(middle) <= 0.18f) low = middle else high = middle
            }
            return Color.rgb((red * low * 255f).toInt(), (green * low * 255f).toInt(),
                (blue * low * 255f).toInt())
        }
    }
}


/**
 * Pure pixel maths for [PressEffect]'s alpha masks (white, ARGB, non-premultiplied),
 * kept free of Android types so it can be checked off-device.
 */
internal object PressEffectMasks {
    const val SHAPES = 8
    const val TEXTURE_SIZE = 256
    const val DOT_SIZE = 64

    fun bodySeed(i: Int) = 11 + i * 26
    fun ringSeed(i: Int) = 7 + i * 31

    fun mist(noise: Noise, seed: Int, ring: Boolean): IntArray {
        val g = Random(seed)
        // random lobes (2..5) make every ripple an irregular, water-like outline
        val ks = intArrayOf(2, 3, 4, 5)
        val amps = FloatArray(4) { (0.5f + g.nextFloat() * 0.5f) / ks[it].toFloat().pow(0.9f) }
        val total = amps.sum()
        val strength = 0.26f + g.nextFloat() * 0.12f
        for (i in amps.indices) amps[i] = amps[i] / total * strength
        val phases = FloatArray(4) { g.nextFloat() * 2f * PI.toFloat() }
        var maxD = 0f
        for (i in 0 until 360) {
            val th = i / 360f * 2f * PI.toFloat()
            maxD = max(maxD, lobes(th, ks, amps, phases))
        }
        val ox = g.nextFloat() * 50f
        val oy = g.nextFloat() * 50f
        val size = TEXTURE_SIZE
        val c = (size - 1) / 2f
        val pixels = IntArray(size * size)
        for (py in 0 until size) {
            for (px in 0 until size) {
                val nx = (px - c) / (size / 2f)
                val ny = (py - c) / (size / 2f)
                val r0 = hypot(nx, ny)
                if (r0 >= 0.995f) continue
                // gentle domain warp: wispy, uneven edge
                val wx = noise.fbm(nx * 1.4f + ox, ny * 1.4f + oy) - 0.5f
                val wy = noise.fbm(nx * 1.4f + oy + 13f, ny * 1.4f + ox + 7f) - 0.5f
                val x = nx + 0.10f * wx
                val y = ny + 0.10f * wy
                val r = hypot(x, y)
                val th = atan2(y, x)
                val rho = r / (lobes(th, ks, amps, phases) / maxD)
                val cloud = (0.66f + 1.2f * (noise.fbm(x * 1.1f + oy, y * 1.1f + ox) - 0.5f)).coerceIn(0.3f, 1.15f)
                val edge = 1f - PressEffect.smoothstep(0.84f, 0.99f, r0)
                val a = if (ring) {
                    val d = rho - 0.72f
                    val w = if (d > 0f) 0.10f else 0.24f
                    0.95f * exp(-(d / w) * (d / w))
                } else {
                    // A filled cloud with broad feathering; no bright hollow ring.
                    val core = exp(-(rho / 0.58f).pow(2))
                    val shoulder = 0.24f * exp(-((rho - 0.42f) / 0.30f).pow(2))
                    (0.86f * core + shoulder) *
                        (1f - PressEffect.smoothstep(0.63f, 1.03f, rho))
                }
                val alpha = (a * cloud * edge).coerceIn(0f, 1f)
                pixels[py * size + px] = ((alpha * 255f + 0.5f).toInt() shl 24) or 0xFFFFFF
            }
        }
        return pixels
    }

    /** A filled rounded front travels under the key grid, without a hollow ring. */
    fun fluid(noise: Noise, seed: Int, phase: Float, size: Int = 128): IntArray {
        val p = phase.coerceIn(0f, 1f)
        val g = Random(seed)
        val phases = FloatArray(4) { g.nextFloat() * 2f * PI.toFloat() }
        val ox = g.nextFloat() * 30f
        val oy = g.nextFloat() * 30f
        val c = (size - 1) / 2f
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val nx = (x - c) / c
            val ny = (y - c) / c
            val theta = atan2(ny, nx)
            // Phase advection gives the cloud internal motion, rather than a static
            // stamp that only gets larger. All of this is prepared off the draw path.
            val advectX = p * 0.55f * cos(phases[3])
            val advectY = p * 0.45f * kotlin.math.sin(phases[3])
            val warp = (noise.fbm(nx * 1.7f + ox + advectX, ny * 1.7f + oy + advectY) - 0.5f)
            val lobes = 1f + p * (0.055f * cos(2f * theta + phases[0] + p * 0.8f) +
                0.035f * cos(3f * theta + phases[1] - p * 0.6f) + 0.025f * cos(5f * theta + phases[2] + p))
            // Retain rounded rectangular corners as the front advances. A low
            // late power would turn the field into a round, floating fog spot.
            val power = 8f - 2f * p
            val footprint = (abs(nx).pow(power) + abs(ny).pow(power)).pow(1f / power)
            // Warping may move a point inside the source footprint. Negative distance
            // cannot enter a fractional power: it would create NaN/transparent holes.
            val distance = ((footprint + warp * 0.045f * p) / lobes).coerceAtLeast(0f)
            // A broad, almost level body makes neighbouring gaps one connected
            // colour field. The front remains soft; the long Gaussian outskirts
            // are gone rather than being made brighter across the entire board.
            val body = 1f - PressEffect.smoothstep(0.40f, 0.90f, distance)
            val texture = 0.98f + p * 0.035f * (noise.fbm(nx * 1.1f + oy + advectY, ny * 1.1f + ox + advectX) - 0.5f)
            val outer = 1f - PressEffect.smoothstep(0.86f, 1f, max(abs(nx), abs(ny)))
            // Balance the squarer late footprint without changing the light
            // budgets, its configured duration, or its maximum travel distance.
            val alpha = (0.98f * (1f - 0.12f * p) * body * texture * outer).coerceIn(0f, 1f)
            pixels[y * size + x] = ((alpha * 255f + 0.5f).toInt() shl 24) or 0xffffff
        }
        return pixels
    }

    fun keycap(size: Int, roundness: Float = 0f): IntArray {
        val pixels = IntArray(size * size)
        val center = (size - 1) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val nx = abs(x - center) / center
            val ny = abs(y - center) / center
            val power = 6f - 4f * roundness.coerceIn(0f, 1f)
            val distance = (nx.pow(power) + ny.pow(power)).pow(1f / power)
            val alpha = 1f - PressEffect.smoothstep(0.9f, 1f, distance)
            pixels[y * size + x] = ((alpha * 255f + 0.5f).toInt() shl 24) or 0xffffff
        }
        return pixels
    }

    fun dot(): IntArray {
        val size = DOT_SIZE
        val c = (size - 1) / 2f
        val pixels = IntArray(size * size)
        for (py in 0 until size) {
            for (px in 0 until size) {
                val r = hypot(px - c, py - c) / (size / 2f)
                val a = exp(-(r / 0.48f).pow(2)) * (1f - PressEffect.smoothstep(0.85f, 0.99f, r))
                pixels[py * size + px] = ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or 0xFFFFFF
            }
        }
        return pixels
    }

    private fun lobes(th: Float, ks: IntArray, amps: FloatArray, phases: FloatArray): Float {
        var d = 1f
        for (i in ks.indices) d += amps[i] * cos(ks[i] * th + phases[i])
        return d
    }

    /** small tiling 2D value noise with a few octaves */
    class Noise(seed: Int) {
        private val grid = Random(seed).let { r -> FloatArray(GRID * GRID) { r.nextFloat() } }

        private fun value(x: Float, y: Float): Float {
            val xf = floor(x)
            val yf = floor(y)
            val ix = xf.toInt()
            val iy = yf.toInt()
            val fx = x - xf
            val fy = y - yf
            val sx = fx * fx * (3f - 2f * fx)
            val sy = fy * fy * (3f - 2f * fy)
            val top = at(ix, iy) + (at(ix + 1, iy) - at(ix, iy)) * sx
            val bottom = at(ix, iy + 1) + (at(ix + 1, iy + 1) - at(ix, iy + 1)) * sx
            return top + (bottom - top) * sy
        }

        private fun at(x: Int, y: Int): Float =
            grid[Math.floorMod(y, GRID) * GRID + Math.floorMod(x, GRID)]

        fun fbm(x: Float, y: Float): Float {
            var sum = 0f
            var amp = 1f
            var freq = 1.5f
            var norm = 0f
            repeat(3) {
                sum += amp * value(x * freq, y * freq)
                norm += amp
                amp *= 0.5f
                freq *= 2f
            }
            return sum / norm
        }

        companion object {
            const val GRID = 64
        }
    }
}
