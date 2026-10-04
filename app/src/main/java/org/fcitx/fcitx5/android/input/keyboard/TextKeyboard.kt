/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.allViews
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, layoutFor(context)) {

    override val slideSelectionEnabled = true

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        /** Rime latin mode, an English keyboard or an English engine all count as English */
        fun isEnglish(ime: InputMethodEntry): Boolean =
            ime.uniqueName.startsWith("keyboard-") ||
                ime.languageCode.startsWith("en") || ime.subMode.icon.startsWith("fcitx_rime_latin") ||
                ime.subMode.label.equals("A", ignoreCase = true)

        /** The space legend is an icon; language state has its own key. */
        fun spaceLabel(@Suppress("UNUSED_PARAMETER") english: Boolean): String = ""

        private val NumberRow = "1234567890".map { digit ->
            KeyDef(
                KeyDef.Appearance.Text(digit.toString(), 20f),
                setOf(KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(digit.toString()))),
                arrayOf(KeyDef.Popup.Preview(digit.toString()))
            )
        }

        private fun layoutFor(context: Context): List<List<KeyDef>> =
            if (ThemeManager.prefs.portraitNumberRow.getValue() &&
                context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            ) listOf(NumberRow) + Layout else Layout

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q"),
                AlphabetKey("W"),
                AlphabetKey("E"),
                AlphabetKey("R"),
                AlphabetKey("T"),
                AlphabetKey("Y"),
                AlphabetKey("U"),
                AlphabetKey("I"),
                AlphabetKey("O"),
                AlphabetKey("P")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")")
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("!#1", SymbolKeyboard.Name, 0.14f),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f, textSize = 18f),
                CommaKey(0.08f, KeyDef.Appearance.Variant.Normal),
                SpaceKey(0.32f),
                SymbolKey(".", 0.08f, KeyDef.Appearance.Variant.Normal),
                LanguageKey(0.10f),
                ReturnKey(0.16f)
            )
        )
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val lang: TextKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
        space.contentDescription = context.getString(R.string.space_key_label)
        space.setSpaceIcon()
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None
    private var englishMode = false

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = if (englishMode) p else punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    if (action.act.length == 1 && action.act[0].isLetter()) when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        super.onAttach()
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        englishMode = isEnglish(ime)
        lang.contentDescription = if (englishMode) "English" else "中文"
        lang.mainText.text = if (englishMode) "英" else "中"
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action.copy(
                        keyboard = KeyDef.Popup.Keyboard.Explicit(
                            action.keyboard.items.map { PunctuationMode.forMode(it, englishMode) }.toTypedArray()
                        )
                    )
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
        // Keep the space centered and the row fully touchable when the language
        // key is hidden: its width belongs to the keys on the right of space.
        `return`.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintPercentWidth = if (visible) 0.16f else 0.26f
        }
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            // The dedicated digit row precedes the letters; skip it without
            // terminating the entire case update.
            if (it.def !is KeyDef.Appearance.AltText) return@forEach
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (!englishMode && keepLettersUppercase) str.uppercase() else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    if (it.id == R.id.button_lang || it.id == R.id.button_space || str.isEmpty() || str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}
