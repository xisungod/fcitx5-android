/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R

object OfflineDictationFactory {
    internal fun create(context: Context, inputType: Int, unfinishedPreedit: Boolean,
        applyTranscript: DictationTranscriptCallback, onAbandon: () -> Unit): OfflineDictationSession {
        val app = context.applicationContext
        val unavailable = when {
            unfinishedPreedit -> app.getString(R.string.offline_dictation_backend_preedit)
            !supportsEditor(inputType) -> app.getString(R.string.offline_dictation_backend_editor_type)
            "arm64-v8a" !in Build.SUPPORTED_ABIS -> app.getString(R.string.offline_dictation_backend_device)
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ->
                app.getString(R.string.offline_dictation_backend_microphone)
            !modelsPresent(app) -> app.getString(R.string.offline_dictation_backend_models)
            else -> null
        }
        return LocalOfflineDictationSession(
            unavailableMessage = unavailable,
            hasPermission = { ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED },
            requestPermission = {
                app.startActivity(Intent(app, OfflineDictationPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            audioFactory = { AndroidDictationAudioSource() },
            engineFactory = { SherpaDictationSpeechEngine(app.assets) },
            applyTranscript = applyTranscript,
            onAbandon = onAbandon,
            failureMessage = { failure -> app.getString(when (failure) {
                DictationFailure.Permission -> R.string.offline_dictation_backend_permission
                DictationFailure.Audio -> R.string.offline_dictation_backend_audio
                DictationFailure.Engine -> R.string.offline_dictation_backend_engine
                DictationFailure.Editor -> R.string.offline_dictation_backend_editor
            }) }
        )
    }

    internal fun supportsEditor(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return inputType and InputType.TYPE_MASK_VARIATION !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }

    private fun modelsPresent(context: Context): Boolean = runCatching {
        val names = context.assets.list(SherpaDictationSpeechEngine.MODEL_PATH).orEmpty().toSet()
        SherpaDictationSpeechEngine.MODEL_FILES.all { it in names } &&
            "model.int8.onnx" in context.assets.list("asr/punctuation").orEmpty()
    }.getOrDefault(false)
}
