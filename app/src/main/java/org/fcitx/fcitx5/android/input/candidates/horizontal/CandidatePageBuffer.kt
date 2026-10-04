/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord

/** Reject late pages after further typing so words and selection indices cannot mix. */
internal class CandidatePageBuffer {
    data class Request(val generation: Int, val offset: Int, val limit: Int)
    var generation = 0
        private set
    var words = emptyArray<CandidateWord>()
        private set
    var total = -1
        private set
    private var pending: Request? = null
    private var exhausted = true

    fun reset(words: Array<CandidateWord>, total: Int) {
        generation++
        this.words = words
        this.total = total
        pending = null
        exhausted = words.isEmpty() || (total >= 0 && words.size >= total)
    }
    fun request(): Request? {
        if (pending != null || exhausted) return null
        return Request(generation, words.size, 48).also { pending = it }
    }
    fun complete(request: Request, page: Array<CandidateWord>): Boolean {
        if (pending != request || generation != request.generation || words.size != request.offset) return false
        pending = null
        words += page
        exhausted = page.size < request.limit || (total >= 0 && words.size >= total)
        if (exhausted && total < 0) total = words.size
        return true
    }
    fun failed(request: Request) {
        if (pending == request) pending = null
    }
}
