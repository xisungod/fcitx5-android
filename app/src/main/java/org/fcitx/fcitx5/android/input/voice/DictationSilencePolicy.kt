/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

/** Main-thread inactivity clock. Loading has its own deadline in the recognition session. */
internal class DictationSilencePolicy(startedAtMillis: Long, private val silenceMillis: Long = 15_000L) {
    private var heardAt = startedAtMillis
    private var lastAudioActivity = 0L
    private var lastState = OfflineDictationState()

    fun onAudioActivity(activity: Long, nowMillis: Long) {
        if (activity != lastAudioActivity) {
            lastAudioActivity = activity
            heardAt = nowMillis
        }
    }

    fun onState(state: OfflineDictationState, nowMillis: Long) {
        if (state.transcript != lastState.transcript ||
            state.phase != lastState.phase || state.loadingModel != lastState.loadingModel) heardAt = nowMillis
        lastState = state
    }

    fun shouldStop(state: OfflineDictationState, nowMillis: Long): Boolean =
        state.phase == OfflineDictationPhase.Recording && !state.loadingModel &&
            nowMillis - heardAt >= silenceMillis
}
