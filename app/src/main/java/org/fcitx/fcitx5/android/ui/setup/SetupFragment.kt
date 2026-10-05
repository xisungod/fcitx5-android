/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.FragmentSetupBinding
import org.fcitx.fcitx5.android.utils.serializable

class SetupFragment : Fragment() {

    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable(PAGE)!! }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSetupBinding.inflate(inflater, container, false)
        sync()
        return binding.root
    }

    // called on window focus changed
    fun sync() {
        val done = page.isDone()
        with(binding) {
            stepText.text = getString(R.string.axiang_setup_step, page.ordinal + 1, SetupPage.entries.size)
            pageTitle.setText(
                if (page == SetupPage.Enable) R.string.axiang_setup_enable_title
                else R.string.axiang_setup_select_title
            )
            hintText.text = page.getHintText(requireContext())
            actionButton.visibility = if (done) View.GONE else View.VISIBLE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
            doneText.visibility = if (done) View.VISIBLE else View.GONE
            doneIcon.visibility = if (done) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val PAGE = "page"
    }

}