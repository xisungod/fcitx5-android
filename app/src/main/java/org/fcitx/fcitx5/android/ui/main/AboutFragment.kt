/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class AboutFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(Preference(context).apply {
                key = "axiang_identity"
                layoutResource = R.layout.axiang_about_identity
                isSelectable = false
            })
            addPreference(R.string.current_version, BuildConfig.VERSION_NAME)
            addPreference(R.string.axiang_about_project, R.string.axiang_about_project_summary) {
                openLink(Const.projectUrl)
            }
            addPreference(R.string.faq) {
                openLink(Const.faqUrl)
            }
            addPreference(R.string.axiang_about_privacy, R.string.axiang_about_privacy_summary) {
                showDetails(
                    getString(R.string.axiang_about_privacy),
                    getString(R.string.axiang_about_privacy_message)
                )
            }
            addCategory(R.string.axiang_about_legal) {
                isIconSpaceReserved = false
                addPreference(Preference(context).apply {
                    key = "axiang_open_source_credits"
                    title = getString(R.string.axiang_about_credits)
                    summary = getString(R.string.axiang_about_credits_summary)
                    isIconSpaceReserved = false
                    isSelectable = false
                })
                addPreference(
                    R.string.open_source_licenses,
                    R.string.licenses_of_third_party_libraries
                ) {
                    navigateWithAnim(SettingsRoute.License)
                }
                addPreference(R.string.license, R.string.axiang_about_license_summary) {
                    openLink(Const.licenseUrl)
                }
            }
            addPreference(R.string.axiang_about_build, R.string.axiang_about_build_summary) {
                showDetails(
                    getString(R.string.axiang_about_build),
                    getString(
                        R.string.axiang_build_details,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.APPLICATION_ID,
                        BuildConfig.BUILD_TYPE,
                        BuildConfig.BUILD_GIT_HASH,
                        formatDateTime(BuildConfig.BUILD_TIME)
                    )
                )
            }
        }
    }

    private fun openLink(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showDetails(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .findViewById<TextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }
}
