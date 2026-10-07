/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RimeTouchProbe
import org.fcitx.fcitx5.android.core.RimeTouchProbePolicy
import org.fcitx.fcitx5.android.ui.main.MainViewModel

/** An explicit synthetic check uses the same native queue and privacy gates as typing. */
class TouchProbeSelfCheckFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var ui: TouchProbeSelfCheckUi? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        TouchProbeSelfCheckUi(requireContext(), ::checkNow).also { ui = it }.root

    override fun onViewCreated(view: View, state: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    ui?.render(RimeTouchProbe.diagnostics())
                    delay(1000)
                }
            }
        }
    }

    private fun checkNow() {
        val currentUi = ui ?: return
        currentUi.setChecking(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val notice = viewModel.fcitx.runOnReady {
                    withInputTransaction {
                        if (inputMethodEntryCached.uniqueName != "rime")
                            R.string.touch_probe_select_pinyin
                        else if (!RimeTouchProbePolicy.allowsQuery(getAddonConfig("rime")))
                            R.string.touch_probe_policy_blocked
                        else {
                            // A fixed public prompt, with no preceding user text. No live
                            // session processing, candidate selection, commit or learning.
                            val result = RimeTouchProbe.query("rime_ice", "nihao", bypassCache = true)
                            if (result.available && !result.withinBudget &&
                                result.coldInitialization && result.nativeWithinBudget)
                                R.string.touch_probe_cold_check_timeout
                            else R.string.touch_probe_check_complete
                        }
                    }
                }
                currentUi.setNotice(notice)
                currentUi.render(RimeTouchProbe.diagnostics())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentUi.setNotice(R.string.touch_probe_check_failed)
            } finally {
                currentUi.setChecking(false)
            }
        }
    }

    override fun onDestroyView() {
        ui = null
        super.onDestroyView()
    }
}
