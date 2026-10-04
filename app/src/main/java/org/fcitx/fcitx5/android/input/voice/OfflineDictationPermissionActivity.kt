/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import org.fcitx.fcitx5.android.R

/** Permission only: granting access never starts or resumes recording. */
class OfflineDictationPermissionActivity : Activity() {
    private var permissionDenied = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.offline_dictation_backend_permission_title)
        permissionDenied = savedInstanceState?.getBoolean("permissionDenied") == true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finishGranted()
        } else if (permissionDenied) {
            showPermissionDenied()
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            finishGranted()
        } else {
            permissionDenied = true
            showPermissionDenied()
        }
    }

    private fun showPermissionDenied() {
        AlertDialog.Builder(this)
                .setTitle(R.string.offline_dictation_backend_permission_title)
                .setMessage(R.string.offline_dictation_backend_permission_denied)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setPositiveButton(R.string.offline_dictation_backend_permission_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionDenied", permissionDenied)
        super.onSaveInstanceState(outState)
    }

    private fun finishGranted() {
        Toast.makeText(this, R.string.offline_dictation_backend_permission_granted, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object { private const val REQUEST_MICROPHONE = 46 }
}
