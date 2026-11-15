/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.rime.RimeMoegirlDictionary
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.toast

/** No network access: the browser can download the published pack and the picker imports it. */
internal class RimeMoegirlDictionaryUi(private val fragment: Fragment) {
    private lateinit var toggle: SwitchPreferenceCompat
    private lateinit var importRow: Preference
    private var busy = false
    private var installed = false
    private var viewModel: MainViewModel? = null

    private fun directory() = FcitxApplication.getInstance().directBootAwareContext.let {
        (it.getExternalFilesDir(null) ?: it.filesDir).resolve("data/rime")
    }

    private val picker = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val context = fragment.context ?: return@registerForActivityResult
        if (uri == null || busy) return@registerForActivityResult
        updateBusy(true)
        fragment.lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    val root = directory()
                    context.contentResolver.openInputStream(uri)?.use { RimeMoegirlDictionary.install(root, it) }
                        ?: error("Cannot open Moegirl dictionary")
                    RimeMoegirlDictionary.state(root)
                }
                render(state)
                context.toast(R.string.rime_moegirl_imported)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RimeMoegirlDictionary.InvalidRelease) {
                context.toast(context.getString(R.string.rime_moegirl_wrong_release, RimeMoegirlDictionary.RELEASE))
            } catch (_: Exception) {
                context.toast(R.string.rime_moegirl_failed)
            } finally {
                updateBusy(false)
            }
        }
    }

    fun attach(screen: PreferenceScreen, model: MainViewModel) {
        viewModel = model
        val context = fragment.requireContext()
        importRow = Preference(context).apply {
            key = KeyboardPreferenceSections.MOEGIRL_IMPORT_KEY
            setTitle(R.string.rime_moegirl_import)
            setSummary(R.string.rime_moegirl_import_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.rime_moegirl_import)
                    .setMessage(context.getString(R.string.rime_moegirl_source, RimeMoegirlDictionary.RELEASE))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.rime_moegirl_open_release) { _, _ ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RimeMoegirlDictionary.RELEASE_URL)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            context.toast(RimeMoegirlDictionary.RELEASE_URL)
                        }
                    }
                    .setPositiveButton(R.string.rime_moegirl_choose_file) { _, _ -> picker.launch(arrayOf("*/*")) }
                    .show()
                true
            }
        }.also(screen::addPreference)
        toggle = SwitchPreferenceCompat(context).apply {
            key = KeyboardPreferenceSections.MOEGIRL_ENABLE_KEY
            setTitle(R.string.rime_moegirl_enable)
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceChangeListener { _, value ->
                if (!busy && installed) changeEnabled(value as Boolean)
                false // The switch follows the engine-side patch after a successful write.
            }
        }.also(screen::addPreference)
        render(runCatching { RimeMoegirlDictionary.state(directory()) }
            .getOrDefault(RimeMoegirlDictionary.State(false, false)))
    }

    private fun updateBusy(value: Boolean) {
        busy = value
        if (::importRow.isInitialized) importRow.isEnabled = !busy
        if (::toggle.isInitialized) toggle.isEnabled = installed && !busy
    }

    private fun render(state: RimeMoegirlDictionary.State) {
        installed = state.installed
        toggle.isChecked = state.enabled
        toggle.setSummary(if (installed) R.string.rime_moegirl_enable_summary else R.string.rime_moegirl_not_installed)
        updateBusy(busy)
    }

    private fun changeEnabled(enabled: Boolean) {
        val context = fragment.context ?: return
        val model = viewModel ?: return
        updateBusy(true)
        fragment.lifecycleScope.launch {
            try {
                val ready = model.fcitx.runOnReady {
                    inputMethodEntryCached.uniqueName == "rime" &&
                        RimeActions.find(statusArea(), "fcitx-rime-deploy") != null
                }
                if (!ready) {
                    context.toast(R.string.rime_select_first)
                    return@launch
                }
                val root = withContext(Dispatchers.IO) { directory() }
                val previous = withContext(Dispatchers.IO) { RimeMoegirlDictionary.state(root) }
                val state = try {
                    val next = withContext(Dispatchers.IO) {
                        RimeMoegirlDictionary.setEnabled(root, enabled)
                        RimeMoegirlDictionary.state(root)
                    }
                    val started = model.fcitx.runOnReady {
                        val action = if (inputMethodEntryCached.uniqueName == "rime")
                            RimeActions.find(statusArea(), "fcitx-rime-deploy") else null
                        if (action == null) false else { activateAction(action.id); true }
                    }
                    if (!started) {
                        withContext(Dispatchers.IO) { RimeMoegirlDictionary.setEnabled(root, previous.enabled) }
                        context.toast(R.string.rime_select_first)
                        return@launch
                    }
                    next
                } catch (error: Exception) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        RimeMoegirlDictionary.setEnabled(root, previous.enabled)
                    }
                    throw error
                }
                render(state)
                context.toast(R.string.rime_deploy_started)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                context.toast(R.string.rime_moegirl_failed)
            } finally {
                updateBusy(false)
            }
        }
    }
}
