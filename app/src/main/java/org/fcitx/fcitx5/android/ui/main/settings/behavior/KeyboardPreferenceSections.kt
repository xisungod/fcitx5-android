/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference

/** Reparents the original controls, retaining their dialogs, dependencies and stored values. */
internal object KeyboardPreferenceSections {
    enum class Page { Keyboard, Feedback, Typing }

    const val APPLY_TYPING_KEY = "axiang_apply_typing"
    const val DICTIONARY_IMPORT_KEY = "rime_personal_dictionary_import"
    const val TOUCH_DIAGNOSTIC_STATUS_KEY = "touch_diagnostic_status"
    const val TOUCH_DIAGNOSTIC_EXPORT_KEY = "touch_diagnostic_export"
    const val TOUCH_DIAGNOSTIC_CLEAR_KEY = "touch_diagnostic_clear"
    const val PINYIN_TOUCH_PROFILE_CLEAR_KEY = "pinyin_touch_profile_clear"
    const val NEXT_WORD_PREDICTION_HELP_KEY = "local_next_word_prediction_help"

    private data class Section(
        val page: Page,
        val key: String,
        @StringRes val title: Int,
        val preferences: List<String>
    )

    private fun sections(prefs: AppPrefs.Keyboard) = with(prefs) {
        listOf(
            Section(Page.Keyboard, "keyboard_dimensions", R.string.axiang_keyboard_dimensions,
                listOf(keyboardHeightPercent.key, keyboardSidePadding.key, keyboardBottomPadding.key)),
            Section(Page.Keyboard, "keyboard_display", R.string.axiang_keyboard_display,
                listOf(popupOnKeyPress.key, keepLettersUppercase.key, expandToolbarByDefault.key,
                    toolbarNumRowOnPassword.key, showLangSwitchKey.key)),
            Section(Page.Keyboard, "keyboard_gestures", R.string.axiang_keyboard_gestures,
                listOf(expandKeypressArea.key, swipeSymbolDirection.key, longPressDelay.key,
                    spaceKeyLongPressBehavior.key, spaceSwipeMoveCursor.key, langSwitchKeyBehavior.key)),
            Section(Page.Feedback, "keyboard_haptics", R.string.axiang_keyboard_haptics,
                listOf(hapticOnKeyPress.key, hapticStrength.key, hapticOnKeyUp.key, hapticOnRepeat.key)),
            Section(Page.Feedback, "keyboard_sound", R.string.axiang_keyboard_sound,
                listOf(soundOnKeyPress.key, soundOnKeyPressVolume.key)),
            Section(Page.Feedback, "keyboard_haptic_details", R.string.axiang_keyboard_haptic_details,
                listOf(buttonPressVibrationMilliseconds.key, buttonPressVibrationAmplitude.key)),
            Section(Page.Typing, "typing_next_word_prediction", R.string.next_word_prediction_section,
                listOf(localNextWordPrediction.key, NEXT_WORD_PREDICTION_HELP_KEY)),
            Section(Page.Typing, "typing_touch_correction", R.string.pinyin_touch_correction_section,
                listOf(pinyinDownOrder.key, pinyinTouchAlternatives.key,
                    pinyinTouchPromotion.key,
                    pinyinTouchCorrection.key, pinyinTouchPersonalization.key,
                    PINYIN_TOUCH_PROFILE_CLEAR_KEY)),
            Section(Page.Typing, "typing_fuzzy", R.string.axiang_typing_fuzzy,
                listOf(rimeFuzzyNl.key, rimeFuzzyZh.key, rimeFuzzyAng.key, APPLY_TYPING_KEY)),
            Section(Page.Typing, "typing_dictionary", R.string.axiang_typing_dictionary,
                listOf(DICTIONARY_IMPORT_KEY)),
            Section(Page.Typing, "typing_candidates", R.string.axiang_typing_candidates,
                listOf(horizontalCandidateStyle.key, expandedCandidateStyle.key,
                    expandedCandidateGridSpanCount.key)),
            Section(Page.Typing, "typing_behavior", R.string.axiang_typing_behavior,
                listOf(inlineSuggestions.key, focusChangeResetKeyboard.key)),
            Section(Page.Typing, "typing_touch_diagnostics", R.string.touch_diagnostic_section,
                listOf(touchBoundarySettling.key, touchDiagnosticLogging.key,
                    TOUCH_DIAGNOSTIC_STATUS_KEY, TOUCH_DIAGNOSTIC_EXPORT_KEY,
                    TOUCH_DIAGNOSTIC_CLEAR_KEY))
        )
    }

    fun arrange(screen: PreferenceScreen, prefs: AppPrefs.Keyboard, page: Page) {
        formatControls(screen, prefs)
        val original = (0 until screen.preferenceCount).map(screen::getPreference)
        val byKey = original.associateBy { it.key }
        val sections = sections(prefs)
        val knownKeys = sections.flatMap { it.preferences }.toSet()
        screen.removeAll()
        sections.filter { it.page == page }.forEach { section ->
            val controls = section.preferences.mapNotNull(byKey::get)
            addSection(screen, section.key, section.title, controls)
        }
        // Future preferences remain discoverable without being duplicated across the three pages.
        if (page == Page.Keyboard) {
            addSection(screen, "keyboard_other", R.string.axiang_keyboard_other,
                original.filter { it.key !in knownKeys })
        }
    }

    private fun addSection(
        screen: PreferenceScreen,
        key: String,
        @StringRes title: Int,
        controls: List<Preference>
    ) {
        if (controls.isEmpty()) return
        val category = PreferenceCategory(screen.context).apply {
            this.key = key
            setTitle(title)
            isIconSpaceReserved = false
            order = screen.preferenceCount
        }
        screen.addPreference(category)
        controls.forEachIndexed { index, preference ->
            preference.order = index
            category.addPreference(preference)
        }
    }

    private fun formatControls(screen: PreferenceScreen, prefs: AppPrefs.Keyboard) {
        screen.findPreference<TwinSeekBarPreference>(prefs.keyboardHeightPercent.key)?.apply {
            setTitle(R.string.keyboard_height_size)
            setDialogTitle(R.string.keyboard_height_size)
            setDialogMessage(R.string.keyboard_height_size_summary)
        }
        screen.findPreference<TwinSeekBarPreference>(prefs.keyboardSidePadding.key)?.apply {
            setTitle(R.string.keyboard_width_size)
            setDialogTitle(R.string.keyboard_width_size)
            setDialogMessage(R.string.keyboard_width_size_summary)
        }
        screen.findPreference<DialogSeekBarPreference>(prefs.hapticStrength.key)?.apply {
            setDialogMessage(R.string.haptic_strength_summary)
        }
        listOf(
            prefs.rimeFuzzyNl.key to R.string.axiang_typing_fuzzy_nl,
            prefs.rimeFuzzyZh.key to R.string.axiang_typing_fuzzy_zh,
            prefs.rimeFuzzyAng.key to R.string.axiang_typing_fuzzy_ang
        ).forEach { (key, title) -> screen.findPreference<Preference>(key)?.setTitle(title) }
    }

    fun addRelated(
        screen: PreferenceScreen,
        vararg destinations: Pair<Int, SettingsRoute>,
        navigate: (SettingsRoute) -> Unit
    ) {
        val controls = destinations.map { (title, route) ->
            Preference(screen.context).apply {
                key = "related_${route.javaClass.simpleName}"
                setTitle(title)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setOnPreferenceClickListener { navigate(route); true }
            }
        }
        addSection(screen, "keyboard_related", R.string.axiang_keyboard_related, controls)
    }
}
