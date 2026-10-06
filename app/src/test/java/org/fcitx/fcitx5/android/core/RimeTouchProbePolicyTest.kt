/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.*
import org.junit.Test

class RimeTouchProbePolicyTest {
    private fun config(deploy: RawConfig = RawConfig("Deploy", ""),
                       sync: RawConfig = RawConfig("Synchronize", "")) = RawConfig(arrayOf(
        RawConfig("cfg", arrayOf(deploy, sync, RawConfig("PreeditMode", "Commit preview"))),
        // Descriptions may contain shortcut examples/defaults and must be ignored.
        RawConfig("desc", arrayOf(RawConfig("Deploy", "Control+Alt+grave")))
    ))

    @Test fun ordinaryLowercaseVirtualAndUnmodifiedLettersKeepTheCache() {
        for (letter in 'a'..'z') {
            assertTrue(RimeTouchProbePolicy.ordinaryKey(letter, 0u))
            assertTrue(RimeTouchProbePolicy.ordinaryKey(letter, KeyStates.Virtual.states))
        }
    }

    @Test fun modifiersUppercaseControlsNonLettersAndUnknownKeysExpireTheCache() {
        for (letter in listOf(null, 'A', 'Z', ' ', '\n', '\b', '\'', '0', '中'))
            assertFalse(RimeTouchProbePolicy.ordinaryKey(letter, KeyStates.Virtual.states))
        for (modifier in listOf(KeyState.Shift, KeyState.CapsLock, KeyState.Ctrl,
                KeyState.Alt, KeyState.Super, KeyState.Repeat, KeyState.HandledMask)) {
            assertFalse(RimeTouchProbePolicy.ordinaryKey('a', modifier.state))
            assertFalse(RimeTouchProbePolicy.ordinaryKey('a', KeyStates.Virtual.states or modifier.state))
        }
    }

    @Test fun actualWrappedNativeValuesWithEmptyActionListsAllowQuery() {
        assertTrue(RimeTouchProbePolicy.allowsQuery(config()))
        assertTrue(RimeTouchProbePolicy.allowsQuery(config(
            RawConfig("Deploy", emptyArray()), RawConfig("Synchronize", emptyArray()))))
    }

    @Test fun customBareLetterDeployIsBlockedAsWellAsModifierDeploy() {
        for (shortcut in listOf("a", "Control+Alt+grave")) {
            assertFalse(RimeTouchProbePolicy.allowsQuery(config(
                RawConfig("Deploy", arrayOf(RawConfig("0", shortcut))))))
            assertFalse(RimeTouchProbePolicy.allowsQuery(config(RawConfig("Deploy", shortcut))))
        }
    }

    @Test fun customSynchronizationActionAlsoDisablesTheCachedExperiment() {
        assertFalse(RimeTouchProbePolicy.allowsQuery(config(
            sync = RawConfig("Synchronize", arrayOf(RawConfig("0", "Control+s"))))))
    }

    @Test fun missingConfigOrMissingActionNodesFailClosed() {
        assertFalse(RimeTouchProbePolicy.allowsQuery(RawConfig()))
        assertFalse(RimeTouchProbePolicy.allowsQuery(RawConfig(arrayOf(
            RawConfig("desc", arrayOf(RawConfig("Deploy", ""), RawConfig("Synchronize", "")))))))
        assertFalse(RimeTouchProbePolicy.allowsQuery(RawConfig(arrayOf(
            RawConfig("cfg", arrayOf(RawConfig("Deploy", "")))))))
        assertFalse(RimeTouchProbePolicy.allowsQuery(RawConfig(arrayOf(
            RawConfig("cfg", arrayOf(RawConfig("Synchronize", "")))))))
    }

    @Test fun ambiguousDuplicatedConfigOrActionNodesFailClosed() {
        val wrapped = config()
        wrapped.subItems = wrapped.subItems!! + wrapped["cfg"]
        assertFalse(RimeTouchProbePolicy.allowsQuery(wrapped))
        val duplicatedAction = config()
        val cfg = duplicatedAction["cfg"]
        cfg.subItems = cfg.subItems!! + RawConfig("Deploy", "")
        assertFalse(RimeTouchProbePolicy.allowsQuery(duplicatedAction))
    }

    @Test fun malformedScalarOrNonemptyBlankActionEntriesFailClosed() {
        assertFalse(RimeTouchProbePolicy.allowsQuery(config(
            RawConfig("Deploy", arrayOf(RawConfig("0", ""))))))
        val malformed = config()
        malformed["cfg"].value = "unexpected"
        assertFalse(RimeTouchProbePolicy.allowsQuery(malformed))
    }
}
