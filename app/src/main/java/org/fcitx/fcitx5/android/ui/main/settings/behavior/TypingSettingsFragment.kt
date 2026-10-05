/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.util.AtomicFile
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.neural.MiniRbtModelPackage
import org.fcitx.fcitx5.android.data.neural.MiniRbtModelStatus
import org.fcitx.fcitx5.android.data.neural.MiniRbtModelStore
import org.fcitx.fcitx5.android.data.neural.ModelState
import org.fcitx.fcitx5.android.data.rime.RimeFuzzyConfig
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
import java.io.File

/** Everyday typing options; all controls keep their existing preference keys and engine actions. */
class TypingSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    private val viewModel: MainViewModel by activityViewModels()
    private val dictionaryImport = RimeDictionaryImportUi(this)
    private val neuralControls = MiniRbtSettingsControls(this)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        dictionaryImport.attach(screen, viewModel)
        neuralControls.attach(screen)
        screen.addPreference(Preference(requireContext()).apply {
            key = KeyboardPreferenceSections.APPLY_TYPING_KEY
            setTitle(R.string.axiang_typing_apply)
            setSummary(R.string.axiang_typing_apply_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener { applyTypingOptions(); true }
        })
        KeyboardPreferenceSections.arrange(screen, AppPrefs.getInstance().keyboard,
            KeyboardPreferenceSections.Page.Typing)
        KeyboardPreferenceSections.addRelated(screen,
            R.string.axiang_keyboard_layout_entry to SettingsRoute.VirtualKeyboard,
            R.string.axiang_typing_candidate_window_entry to SettingsRoute.CandidatesWindow) {
            navigateWithAnim(it)
        }
    }

    private fun applyTypingOptions() {
        val context = requireContext()
        lifecycleScope.launch {
            try {
                val ready = viewModel.fcitx.runOnReady {
                    inputMethodEntryCached.uniqueName == "rime" &&
                        RimeActions.find(statusArea(), "fcitx-rime-deploy") != null
                }
                if (!ready) {
                    context.toast(context.getString(R.string.rime_select_first))
                    return@launch
                }
                val prefs = AppPrefs.getInstance().keyboard
                val config = RimeFuzzyConfig.render(prefs.rimeFuzzyNl.getValue(),
                    prefs.rimeFuzzyZh.getValue(), prefs.rimeFuzzyAng.getValue())
                withContext(Dispatchers.IO) {
                    val engineContext = FcitxApplication.getInstance().directBootAwareContext
                    val directory = (engineContext.getExternalFilesDir(null) ?: engineContext.filesDir)
                        .resolve("data/rime")
                    check(directory.isDirectory || directory.mkdirs())
                    val file = AtomicFile(File(directory, "xuancai_mobile.yaml"))
                    val output = file.startWrite()
                    try {
                        output.write(config.toByteArray(Charsets.UTF_8))
                        file.finishWrite(output)
                    } catch (error: Exception) {
                        file.failWrite(output)
                        throw error
                    }
                }
                viewModel.fcitx.runOnReady {
                    RimeActions.find(statusArea(), "fcitx-rime-deploy")?.let { activateAction(it.id) }
                }
                context.toast(context.getString(R.string.rime_deploy_started))
            } catch (error: Exception) {
                context.toast(error)
            }
        }
    }
}

/** Download and activation are independent user actions; opening settings never uses the network. */
private class MiniRbtSettingsControls(private val fragment: Fragment) {
    private val prefs get() = AppPrefs.getInstance().keyboard.miniRbtEnabled

    fun attach(screen: PreferenceScreen) {
        val context = fragment.requireContext()
        val store = MiniRbtModelStore.get(context)
        val toggle = requireNotNull(screen.findPreference<Preference>(prefs.key)).apply {
            setSummary(R.string.minirbt_enabled_summary)
            setOnPreferenceChangeListener { _, value ->
                if (value == true && !store.state.value.ready) {
                    context.toast(context.getString(R.string.minirbt_need_model))
                    false
                } else {
                    // Main.immediate may run before SwitchPreference persists the new value.
                    render(store.state.value, value == true)
                    true
                }
            }
        }
        val model = Preference(context).apply {
            key = KeyboardPreferenceSections.MINIRBT_MODEL_KEY
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                this@MiniRbtSettingsControls.fragment.lifecycleScope.launch {
                    when (store.state.value.status) {
                        MiniRbtModelStatus.DOWNLOADING -> store.cancelDownload()
                        MiniRbtModelStatus.NOT_DOWNLOADED, MiniRbtModelStatus.ERROR -> store.download()
                        else -> Unit
                    }
                }
                true
            }
        }
        val delete = Preference(context).apply {
            key = KeyboardPreferenceSections.MINIRBT_DELETE_KEY
            setTitle(R.string.minirbt_delete)
            setSummary(R.string.minirbt_delete_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                AlertDialog.Builder(context).setTitle(R.string.minirbt_delete)
                    .setMessage(R.string.minirbt_delete_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.minirbt_delete) { _, _ ->
                        this@MiniRbtSettingsControls.fragment.lifecycleScope.launch { store.delete() }
                    }.show()
                true
            }
        }
        screen.addPreference(model)
        screen.addPreference(delete)
        controls = Controls(toggle, model, delete)
        render(store.state.value)
        fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.state.collect(::render)
            }
        }
        // This is an explicit settings visit. A test-only asset may be prepared locally.
        fragment.lifecycleScope.launch { store.bootstrapBundled() }
    }

    private data class Controls(val toggle: Preference, val model: Preference, val delete: Preference)
    private var controls: Controls? = null

    private fun render(state: ModelState) = render(state, prefs.getValue())

    private fun render(state: ModelState, active: Boolean) {
        val context = fragment.context ?: return
        val ui = controls ?: return
        ui.toggle.isEnabled = state.ready
        ui.delete.isVisible = state.ready
        ui.model.isEnabled = state.status != MiniRbtModelStatus.VERIFYING
        when (state.status) {
            MiniRbtModelStatus.NOT_DOWNLOADED -> {
                ui.model.setTitle(R.string.minirbt_download)
                val megabytes = String.format(java.util.Locale.ROOT, "%.1f",
                    MiniRbtModelPackage.spec.archiveBytes / (1024.0 * 1024.0))
                ui.model.summary = context.getString(R.string.minirbt_download_summary, megabytes)
            }
            MiniRbtModelStatus.VERIFYING -> {
                ui.model.setTitle(R.string.minirbt_verifying)
                ui.model.summary = null
            }
            MiniRbtModelStatus.DOWNLOADING -> {
                ui.model.title = context.getString(R.string.minirbt_downloading, state.progress ?: 0)
                ui.model.setSummary(R.string.minirbt_cancel)
            }
            MiniRbtModelStatus.READY -> {
                ui.model.setTitle(R.string.minirbt_ready)
                ui.model.setSummary(if (active) R.string.minirbt_active_summary
                    else R.string.minirbt_ready_summary)
            }
            MiniRbtModelStatus.ERROR -> {
                val invalid = state.error == "invalid_model"
                ui.model.setTitle(if (invalid) R.string.minirbt_invalid else R.string.minirbt_retry)
                ui.model.setSummary(if (invalid) R.string.minirbt_invalid_summary else R.string.minirbt_retry_summary)
            }
        }
    }
}
