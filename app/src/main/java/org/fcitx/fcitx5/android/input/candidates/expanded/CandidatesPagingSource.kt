/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.neural.RankedCandidateBatch
import timber.log.Timber

class CandidatesPagingSource(val fcitx: FcitxConnection, val total: Int, val offset: Int,
    private val rankedHead: RankedCandidateBatch,
    private val isCurrent: () -> Boolean) :
    PagingSource<Int, RankedCandidateBatch.Entry>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, RankedCandidateBatch.Entry> {
        if (!isCurrent()) return LoadResult.Invalid()
        // use candidate index for key, null means load from beginning (with offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getCandidates(offset=$startIndex, limit=$pageSize)")
        val candidates = fcitx.runOnReady {
            getCandidates(startIndex, pageSize)
        }
        if (!isCurrent() || !rankedHead.matchesNativePage(startIndex, candidates)) return LoadResult.Invalid()
        val prevKey = if (startIndex >= pageSize) startIndex - pageSize else null
        val nextKey = if (total > 0) {
            if (startIndex + candidates.size >= total) null else startIndex + pageSize
        } else {
            if (candidates.size < pageSize) null else startIndex + pageSize
        }
        return LoadResult.Page(rankedHead.page(startIndex, candidates), prevKey, nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, RankedCandidateBatch.Entry>) = null

}
