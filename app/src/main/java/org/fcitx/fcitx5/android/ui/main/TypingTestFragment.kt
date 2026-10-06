/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.typingtest.TypingTestSession
import org.fcitx.fcitx5.android.utils.toast

/** Opt-in, known-target exercise. It never activates measurement in another editor. */
class TypingTestFragment : Fragment() {
    private var ui: TypingTestUi? = null
    private var originalSoftInputMode: Int? = null
    private var exportSnapshot: String? = null
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = askToAbort()
    }
    private val document = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exportSnapshot ?: TypingTestSession.exportReport()
        exportSnapshot = null
        if (uri == null) return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        if (snapshot == null) {
            context.toast(context.getString(R.string.typing_test_report_missing))
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(snapshot.toByteArray(Charsets.UTF_8))
                    } ?: error("Test report destination unavailable")
                }
                context.toast(context.getString(R.string.typing_test_export_done))
            } catch (_: Exception) {
                context.toast(context.getString(R.string.typing_test_export_failed))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        lifecycleScope.launch { TypingTestSession.loadHistory(context) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        originalSoftInputMode = requireActivity().window.attributes.softInputMode
        // Edge-to-edge content uses explicit IME insets so the same field works in landscape.
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        return TypingTestUi(requireContext(), TypingTestUi.Actions(
            start = { count -> TypingTestSession.start(requireContext(), count, R.id.typing_test_input) },
            complete = ::completePhrase,
            next = { TypingTestSession.nextPhrase() },
            abort = ::askToAbort,
            export = ::exportReport,
            clear = ::clearReports,
            focusChanged = { updateActive() },
            retry = { TypingTestSession.returnToIntro() }
        )).also { ui = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TypingTestSession.state.collect { state ->
                    val screen = TypingTestUi.State(
                        phase = TypingTestUi.Phase.valueOf(state.phase.name),
                        promptId = state.prompt?.id,
                        target = state.prompt?.text.orEmpty(),
                        pinyin = state.prompt?.pinyin.orEmpty(),
                        index = state.index,
                        total = state.total,
                        completed = state.completed,
                        lastCommittedText = state.lastCommittedText,
                        reportSummary = state.reportSummary,
                        reportAvailable = state.reportAvailable,
                        failure = state.failure
                    )
                    val previousInput = ui?.input
                    ui?.render(screen)
                    back.isEnabled = screen.phase == TypingTestUi.Phase.Typing ||
                        screen.phase == TypingTestUi.Phase.Completed
                    if (screen.phase == TypingTestUi.Phase.Typing) {
                        val edit = ui?.input
                        if (edit !== previousInput) edit?.post { focusInput(edit, restart = true) }
                    } else hideKeyboard()
                    updateActive()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ui?.input?.let { focusInput(it, restart = false) }
        updateActive()
    }

    override fun onPause() {
        TypingTestSession.setActive(false)
        super.onPause()
    }

    override fun onDestroyView() {
        TypingTestSession.onViewDestroyed()
        ui?.input?.clearFocus()
        ui = null
        originalSoftInputMode?.let { activity?.window?.setSoftInputMode(it) }
        originalSoftInputMode = null
        super.onDestroyView()
    }

    private fun updateActive() {
        TypingTestSession.setActive(viewLifecycleOwnerLiveData.value?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) == true && ui?.input?.hasFocus() == true)
    }

    private fun focusInput(edit: EditText, restart: Boolean) {
        if (viewLifecycleOwnerLiveData.value?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) != true || ui?.input !== edit) return
        val context = context ?: return
        edit.requestFocus()
        updateActive()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (restart) manager.restartInput(edit)
        manager.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun completePhrase() {
        val edit = ui?.input ?: return
        if (BaseInputConnection.getComposingSpanStart(edit.text) >= 0) {
            requireContext().toast(getString(R.string.typing_test_composing))
            return
        }
        if (edit.text.isNullOrEmpty()) {
            requireContext().toast(getString(R.string.typing_test_empty))
            return
        }
        TypingTestSession.completePhrase(edit.text.toString())
    }

    private fun askToAbort() {
        TypingTestSession.setActive(false)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.typing_test_abort_title)
            .setMessage(R.string.typing_test_abort_message)
            .setNegativeButton(R.string.typing_test_continue, null)
            .setPositiveButton(R.string.typing_test_abort) { _, _ -> TypingTestSession.abort() }
            .setOnDismissListener { updateActive() }
            .show()
    }

    private fun hideKeyboard() {
        val root = ui?.root ?: return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun exportReport() {
        val report = TypingTestSession.exportReport()
        if (report == null) {
            requireContext().toast(getString(R.string.typing_test_report_missing))
            return
        }
        exportSnapshot = report
        document.launch("AXiang-typing-test-${System.currentTimeMillis()}.json")
    }

    private fun clearReports() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.typing_test_clear_title)
            .setMessage(R.string.typing_test_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.typing_test_clear) { _, _ ->
                lifecycleScope.launch {
                    TypingTestSession.clearReports()
                    context?.toast(getString(R.string.typing_test_cleared))
                }
            }
            .show()
    }
}
