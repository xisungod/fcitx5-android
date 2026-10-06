/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.util.AtomicFile
import androidx.activity.result.contract.ActivityResultContracts
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
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticSnapshot
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticStatus
import org.fcitx.fcitx5.android.data.diagnostics.TouchDiagnosticStore
import org.fcitx.fcitx5.android.data.rime.RimeFuzzyConfig
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchProfileStore
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Everyday typing options; all controls keep their existing preference keys and engine actions. */
class TypingSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    private val viewModel: MainViewModel by activityViewModels()
    private val dictionaryImport = RimeDictionaryImportUi(this)
    private val touchDiagnostics = TouchDiagnosticSettingsControls(this)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        dictionaryImport.attach(screen, viewModel)
        touchDiagnostics.attach(screen)
        screen.addPreference(Preference(requireContext()).apply {
            key = KeyboardPreferenceSections.PINYIN_TOUCH_PROFILE_CLEAR_KEY
            setTitle(R.string.pinyin_touch_profile_clear)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val context = requireContext()
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { PinyinTouchProfileStore.clear(context) }
                        context.toast(context.getString(R.string.pinyin_touch_profile_clear_done))
                    } catch (error: Exception) {
                        context.toast(error)
                    }
                }
                true
            }
        })
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

/** Stage-one single-variable experiment and explicitly consented local diagnostic export. */
private class TouchDiagnosticSettingsControls(private val fragment: Fragment) {
    private var preview: TouchDiagnosticSnapshot? = null
    private var statusPreference: Preference? = null
    private val document = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        val snapshot = preview
        preview = null
        if (uri == null || snapshot == null) return@registerForActivityResult
        val context = fragment.context ?: return@registerForActivityResult
        fragment.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        TouchDiagnosticStore.get(context).export(snapshot, output)
                    } ?: error("Diagnostic destination unavailable")
                }
                context.toast(context.getString(R.string.touch_diagnostic_export_done))
            } catch (_: Exception) {
                context.toast(context.getString(R.string.touch_diagnostic_export_failed))
            }
        }
    }

    fun attach(screen: PreferenceScreen) {
        val context = fragment.requireContext()
        val prefs = AppPrefs.getInstance().keyboard
        val store = TouchDiagnosticStore.get(context)
        screen.findPreference<Preference>(prefs.touchBoundarySettling.key)?.apply {
            setSummary(R.string.touch_boundary_settling_summary)
        }
        screen.findPreference<Preference>(prefs.touchDiagnosticLogging.key)?.apply {
            setSummary(R.string.touch_diagnostic_logging_summary)
        }
        statusPreference = Preference(context).apply {
            key = KeyboardPreferenceSections.TOUCH_DIAGNOSTIC_STATUS_KEY
            setTitle(R.string.touch_diagnostic_status)
            isIconSpaceReserved = false
            isSelectable = false
        }.also(screen::addPreference)
        screen.addPreference(Preference(context).apply {
            key = KeyboardPreferenceSections.TOUCH_DIAGNOSTIC_EXPORT_KEY
            setTitle(R.string.touch_diagnostic_export)
            setSummary(R.string.touch_diagnostic_export_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                this@TouchDiagnosticSettingsControls.fragment.lifecycleScope.launch {
                    try {
                        val snapshot = store.snapshot()
                        if (snapshot.bytes == 0) {
                            context.toast(context.getString(R.string.touch_diagnostic_empty))
                            return@launch
                        }
                        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        val range = listOfNotNull(snapshot.oldestTime, snapshot.newestTime)
                            .map { date.format(Date(it)) }.joinToString(" – ")
                        AlertDialog.Builder(context)
                            .setTitle(R.string.touch_diagnostic_export_preview)
                            .setMessage(context.getString(R.string.touch_diagnostic_export_preview_message,
                                snapshot.fileCount, snapshot.recordCount,
                                snapshot.bytes / 1024, range))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.touch_diagnostic_export_consent) { _, _ ->
                                preview = snapshot
                                document.launch("AXiang-touch-diagnostics.jsonl")
                            }.show()
                    } catch (_: Exception) {
                        context.toast(context.getString(R.string.touch_diagnostic_export_failed))
                    }
                }
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = KeyboardPreferenceSections.TOUCH_DIAGNOSTIC_CLEAR_KEY
            setTitle(R.string.touch_diagnostic_clear)
            setSummary(R.string.touch_diagnostic_clear_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                AlertDialog.Builder(context).setTitle(R.string.touch_diagnostic_clear)
                    .setMessage(R.string.touch_diagnostic_clear_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.touch_diagnostic_clear) { _, _ ->
                        preview = null
                        this@TouchDiagnosticSettingsControls.fragment.lifecycleScope.launch {
                            try {
                                store.clear()
                                context.toast(context.getString(R.string.touch_diagnostic_clear_done))
                            } catch (_: Exception) {
                                context.toast(context.getString(R.string.touch_diagnostic_clear_failed))
                            }
                        }
                    }.show()
                true
            }
        })
        render(store.status.value)
        fragment.lifecycleScope.launch {
            fragment.repeatOnLifecycle(Lifecycle.State.STARTED) { store.status.collect(::render) }
        }
        fragment.lifecycleScope.launch { runCatching { store.snapshot() } }
    }

    private fun render(state: TouchDiagnosticStatus) {
        val context = fragment.context ?: return
        statusPreference?.summary = context.getString(R.string.touch_diagnostic_status_summary,
            state.files, state.records, state.bytes / 1024, state.droppedRecords) +
            if (state.failed) "\n" + context.getString(R.string.touch_diagnostic_storage_failed) else ""
    }
}
