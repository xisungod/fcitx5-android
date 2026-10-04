/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.util.AtomicFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.rime.RimePersonalDictionary
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SAF keeps dictionary selection under the user's control; imports merge rather than erase. */
internal class RimeDictionaryImportUi(private val fragment: Fragment) {
    private var model: MainViewModel? = null
    private var importing = false
    private var preference: Preference? = null
    private val picker = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importing) return@registerForActivityResult
        val context = fragment.context ?: return@registerForActivityResult
        val viewModel = model ?: return@registerForActivityResult
        importing = true
        preference?.isEnabled = false
        fragment.lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        val output = ByteArrayOutputStream()
                        while (output.size() <= RimePersonalDictionary.MAX_BYTES) {
                            val count = input.read(buffer, 0,
                                minOf(buffer.size, RimePersonalDictionary.MAX_BYTES + 1 - output.size()))
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Unable to read dictionary")
                    require(bytes.size <= RimePersonalDictionary.MAX_BYTES) { "Dictionary is too large" }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val incoming = RimePersonalDictionary.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString())
                    val engineContext = FcitxApplication.getInstance().directBootAwareContext
                    val directory = (engineContext.getExternalFilesDir(null) ?: engineContext.filesDir).resolve("data/rime")
                    check(directory.isDirectory || directory.mkdirs())
                    val file = AtomicFile(File(directory, RimePersonalDictionary.FILE_NAME))
                    val existing = if (file.baseFile.exists()) {
                        require(file.baseFile.length() <= RimePersonalDictionary.MAX_BYTES)
                        RimePersonalDictionary.readStored(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                    } else emptyList()
                    val merged = RimePersonalDictionary.merge(existing, incoming)
                    val encoded = RimePersonalDictionary.render(merged).toByteArray(Charsets.UTF_8)
                    require(encoded.size <= RimePersonalDictionary.MAX_BYTES) { "Merged dictionary is too large" }
                    val output = file.startWrite()
                    try {
                        output.write(encoded)
                        file.finishWrite(output)
                    } catch (error: Exception) { file.failWrite(output); throw error }
                    incoming.size
                }
                val deployed = viewModel.fcitx.runOnReady {
                    if (inputMethodEntryCached.uniqueName != "rime") false else {
                        val action = RimeActions.find(statusArea(), "fcitx-rime-deploy")
                        if (action == null) false else { activateAction(action.id); true }
                    }
                }
                context.toast(context.getString(if (deployed) R.string.rime_dictionary_import_deploy
                    else R.string.rime_dictionary_import_saved, count))
            } catch (error: RimePersonalDictionary.InvalidLine) {
                context.toast(context.getString(R.string.rime_dictionary_import_bad_line, error.lineNumber))
            } catch (error: Exception) {
                context.toast(context.getString(R.string.rime_dictionary_import_failed))
            } finally {
                importing = false
                preference?.isEnabled = true
            }
        }
    }

    fun attach(screen: PreferenceScreen, viewModel: MainViewModel) {
        model = viewModel
        preference = Preference(fragment.requireContext()).apply {
            key = "rime_personal_dictionary_import"
            setTitle(R.string.rime_dictionary_import)
            setSummary(R.string.rime_dictionary_import_summary)
            order = 4
            isEnabled = !importing
            setOnPreferenceClickListener {
                picker.launch(arrayOf("text/plain", "text/tab-separated-values", "application/octet-stream"))
                true
            }
        }.also(screen::addPreference)
    }
}
