/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Lifecycle gates for the cached read-only translator. No native work here. */
internal object RimeTouchProbePolicy {
    fun ordinaryKey(character: Char?, states: UInt): Boolean = character != null &&
        character in 'a'..'z' && (states == 0u || states == KeyStates.Virtual.states)

    /**
     * Native getAddonConfig returns { cfg: actual values, desc: descriptions }.
     * A custom deploy shortcut can even be a bare letter, so a cached translator
     * is used only when both lifecycle action lists are verifiably empty.
     * Unknown or malformed config fails closed; descriptions are never settings.
     */
    fun allowsQuery(addonConfig: RawConfig): Boolean {
        val cfg = addonConfig.uniqueChild("cfg") ?: return false
        if (cfg.value.isNotBlank()) return false
        return listOf("Deploy", "Synchronize").all { name ->
            val action = cfg.uniqueChild(name) ?: return@all false
            action.value.isBlank() && action.subItems.isNullOrEmpty()
        }
    }

    private fun RawConfig.uniqueChild(name: String): RawConfig? =
        subItems?.filter { it.name == name }?.singleOrNull()
}
