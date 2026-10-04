/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.util.AtomicFile
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.rime.RimeFuzzyConfig
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.toast
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference
import java.io.File

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    private val viewModel: MainViewModel by activityViewModels()
    private val dictionaryImport = RimeDictionaryImportUi(this)
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        KeyboardQuickControls.attach(screen, AppPrefs.getInstance().keyboard)
        dictionaryImport.attach(screen, viewModel)
        listOf("rime_fuzzy_nl", "rime_fuzzy_zh", "rime_fuzzy_ang").forEachIndexed { index, key ->
            screen.findPreference<Preference>(key)?.order = index
        }
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.rime_apply)
            setSummary(R.string.rime_apply_summary)
            order = 3
            setOnPreferenceClickListener {
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
                            val directory = (engineContext.getExternalFilesDir(null) ?: engineContext.filesDir).resolve("data/rime")
                            check(directory.isDirectory || directory.mkdirs())
                            val file = AtomicFile(File(directory, "xuancai_mobile.yaml"))
                            val output = file.startWrite()
                            try {
                                output.write(config.toByteArray(Charsets.UTF_8))
                                file.finishWrite(output)
                            } catch (e: Exception) { file.failWrite(output); throw e }
                        }
                        viewModel.fcitx.runOnReady {
                            RimeActions.find(statusArea(), "fcitx-rime-deploy")?.let { activateAction(it.id) }
                        }
                        context.toast(context.getString(R.string.rime_deploy_started))
                    } catch (e: Exception) { context.toast(e) }
                }
                true
            }
        })
    }
}

/** Reuse the existing saved percentages, margins and vibration controls in one easy-to-find group. */
internal object KeyboardQuickControls {
    fun attach(screen: PreferenceScreen, prefs: AppPrefs.Keyboard) {
        val category = PreferenceCategory(screen.context).apply {
            key = "keyboard_size_feedback"
            setTitle(R.string.keyboard_size_feedback)
            order = -100
        }
        screen.addPreference(category)
        listOf(prefs.keyboardHeightPercent.key, prefs.keyboardSidePadding.key,
            prefs.hapticStrength.key, prefs.hapticOnKeyPress.key).forEachIndexed { order, key ->
            screen.findPreference<Preference>(key)?.let { preference ->
                screen.removePreference(preference)
                preference.order = order
                category.addPreference(preference)
                when (key) {
                    prefs.keyboardHeightPercent.key -> (preference as? TwinSeekBarPreference)?.apply {
                        setTitle(R.string.keyboard_height_size)
                        setDialogTitle(R.string.keyboard_height_size)
                        setDialogMessage(R.string.keyboard_height_size_summary)
                    }
                    prefs.keyboardSidePadding.key -> (preference as? TwinSeekBarPreference)?.apply {
                        setTitle(R.string.keyboard_width_size)
                        setDialogTitle(R.string.keyboard_width_size)
                        setDialogMessage(R.string.keyboard_width_size_summary)
                    }
                    prefs.hapticStrength.key -> (preference as? DialogSeekBarPreference)?.apply {
                        setDialogMessage(R.string.haptic_strength_summary)
                    }
                }
            }
        }
        category.addPreference(Preference(screen.context).apply {
            setSummary(R.string.haptic_strength_summary)
            isSelectable = false
            isIconSpaceReserved = false
            order = 4
        })
    }
}
