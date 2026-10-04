/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.rime

internal object RimeFuzzyConfig {
    fun render(nl: Boolean, zh: Boolean, ang: Boolean): String {
        val rules = mutableListOf<String>()
        if (nl) rules += listOf("derive/^n/l/", "derive/^l/n/")
        if (zh) rules += listOf("derive/^([zcs])h/$1/", "derive/^([zcs])([^h])/$1h$2/")
        if (ang) rules += listOf("derive/ang$/an/", "derive/an$/ang/")
        return if (rules.isEmpty()) "# Managed by Xuancai\npatch: {}\n" else buildString {
            append("# Managed by Xuancai\npatch:\n  speller/algebra/+:\n")
            rules.forEach { append("    - '").append(it).append("'\n") }
        }
    }
}
