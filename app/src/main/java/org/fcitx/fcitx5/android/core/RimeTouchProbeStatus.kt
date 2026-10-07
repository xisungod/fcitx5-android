/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Only bounded machine codes and a version reach the local test report. */
internal data class RimeTouchProbeStatus(val code: String, val runtimeVersion: String? = null) {
    val rejectionReason: String
        get() = "ProbeUnavailable:${if (code == "Ready") "NativeCreateFailed" else code}"

    companion object {
        const val EXPECTED_VERSION = "1.16.1"
        private val version = Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")
        private val codes = setOf("Ready", "VersionMismatch", "RuntimeUnavailable",
            "MaintenanceMode", "ConfigUnavailable", "SchemaUnavailable", "TranslatorUnavailable",
            "UnsupportedSchema", "WrongThread", "AlreadyCreated", "JniFailure", "CreateFailed", "SchemaCloneFailed",
            "PrivacyGuardFailed", "DictionaryUnavailable", "QueryFailed", "HandleUnavailable")

        fun parseNative(value: String): RimeTouchProbeStatus {
            val parts = value.split(':', limit = 2)
            val code = parts[0].takeIf { it in codes } ?: "UnknownNativeFailure"
            val runtime = parts.getOrNull(1)?.takeIf { version.matches(it) }
            return RimeTouchProbeStatus(code, runtime)
        }
    }
}
