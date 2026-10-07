/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import org.fcitx.fcitx5.android.core.LibimeNextWordPredictor
import java.io.File

/** Public ARM model pools replayed through the exact production Kotlin backend. */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected frozen gold TSV, native pool TSV, completion asset" }
    val rows = File(args[0]).readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split('\t') }
    require(rows.size == 200 && rows.all { it.size == 6 })
    val pools = LinkedHashMap<String, List<String>>()
    for (line in File(args[1]).readLines()) {
        val parts = line.split('\t')
        require(parts.size == 2 && !pools.containsKey(parts[0]))
        pools[parts[0]] = parts[1].takeIf(String::isNotEmpty)?.split(',').orEmpty().distinct()
    }
    require(pools.size == 200 && rows.all { pools.containsKey(it[3]) })
    val asset = File(args[2])
    val backend = NextWordSuggestionBackend(
        nativeQuery = { context, limit ->
            val pool = pools.getValue(context)
            LibimeNextWordPredictor.Result(pool.take(limit), 0L, suggestionPool = pool)
        }, loadIndex = asset::readBytes)
    for (row in rows) {
        val result = backend.query(row[3], 5)
        check(result.available && result.completionAvailable == true) { "Actual completion asset unavailable" }
        check(result.candidates.size <= 5 && result.candidates.distinct() == result.candidates)
        check(result.candidates.all(NextWordPredictionRuntime::isHanText))
        println("PUBLIC_PREDICTION_BENCHMARK\t${row[0]}\t${row[3]}\t${result.candidates.joinToString(",")}")
    }
}
