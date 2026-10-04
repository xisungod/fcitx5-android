/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.status

import org.fcitx.fcitx5.android.core.Action

/** Only a presentation filter: native actions remain intact for schema and language switching. */
internal fun compactStatusActions(actions: Array<Action>): List<Action> = actions.filter { action ->
    !action.isSeparator && (!action.name.startsWith("fcitx-rime-") ||
        action.name.endsWith("-traditionalization") ||
        action.name.endsWith("-simplification") || action.name.endsWith("-full_shape"))
}
