/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.util.AtomicFile
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
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

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        dictionaryImport.attach(screen, viewModel)
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
