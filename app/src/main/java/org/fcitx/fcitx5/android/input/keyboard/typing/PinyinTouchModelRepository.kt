/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Loading is entirely off the input queue. Until ready, every key retains its literal value. */
internal class PinyinTouchModelRepository(private val context: Context) {
    @Volatile
    var decider: PinyinSpatialKeyDecider? = null
        private set

    @Volatile
    var languageModel: PinyinTouchLanguageModel? = null
        private set

    @Volatile
    var profileStore: PinyinTouchProfileStore? = null
        private set

    @Volatile
    private var started = false

    /** Lifecycle retry after first unlock; never read credential storage on the key queue. */
    fun refreshProfile(scope: CoroutineScope) {
        val store = profileStore ?: return
        if (store.isPreloaded) return
        scope.launch(Dispatchers.IO) { store.preload() }
    }

    @Synchronized
    fun preload(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            profileStore = runCatching { PinyinTouchProfileStore(context) }.getOrNull()
            decider = runCatching {
                context.assets.open("typing/pinyin_touch_model.tsv").bufferedReader().use {
                    PinyinTouchLanguageModel.parse(it).also { model -> languageModel = model }
                        .let(::PinyinSpatialKeyDecider)
                }
            }.getOrNull()
        }
    }
}
