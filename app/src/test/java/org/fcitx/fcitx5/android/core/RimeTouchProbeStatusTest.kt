/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class RimeTouchProbeStatusTest {
    @Test fun readyIncludesActualRuntimeVersion() {
        val status = RimeTouchProbeStatus.parseNative("Ready:1.16.1")
        assertEquals("Ready", status.code)
        assertEquals("1.16.1", status.runtimeVersion)
    }

    @Test fun mismatchKeepsActualVersionAndDistinctFailure() {
        val status = RimeTouchProbeStatus.parseNative("VersionMismatch:1.12.0")
        assertEquals("1.12.0", status.runtimeVersion)
        assertEquals("ProbeUnavailable:VersionMismatch", status.rejectionReason)
    }

    @Test fun componentAndPrivacyFailuresRemainDistinct() {
        for (code in listOf("MaintenanceMode", "ConfigUnavailable", "SchemaUnavailable",
                "TranslatorUnavailable", "RuntimeUnavailable", "UnsupportedSchema", "WrongThread", "AlreadyCreated",
                "JniFailure", "CreateFailed", "SchemaCloneFailed", "PrivacyGuardFailed",
                "DictionaryUnavailable", "QueryFailed", "HandleUnavailable")) {
            assertEquals("ProbeUnavailable:$code",
                RimeTouchProbeStatus.parseNative("$code:1.16.1").rejectionReason)
        }
    }

    @Test fun exceptionTextAndPathsCannotEnterTheReport() {
        val status = RimeTouchProbeStatus.parseNative("failure opening /private/user-dictionary")
        assertEquals("UnknownNativeFailure", status.code)
        assertNull(status.runtimeVersion)
        assertEquals("ProbeUnavailable:UnknownNativeFailure", status.rejectionReason)
    }

    @Test fun versionMetadataDoesNotAcceptFreeText() {
        for (suffix in listOf("1.16.1 /private/path", "1.16.1:detail", "", "1.16", "v1.16.1"))
            assertNull(RimeTouchProbeStatus.parseNative("CreateFailed:$suffix").runtimeVersion)
    }

    @Test fun failedHandleCannotBeReportedAsReady() {
        assertEquals("ProbeUnavailable:NativeCreateFailed",
            RimeTouchProbeStatus.parseNative("Ready:1.16.1").rejectionReason)
    }
}
