/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.fcitx.fcitx5.android.tools.typingreplay

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import org.fcitx.fcitx5.android.input.keyboard.typing.*
import kotlin.math.exp

/** No target labels or candidate strings are supplied to this process. */
private class CountingModel(private val actual: NextLetterProbabilityModel) : NextLetterProbabilityModel {
    var calls = 0
    var nanos = 0L
    var longestPrefix = 0
    fun reset() { calls = 0; nanos = 0; longestPrefix = 0 }
    override fun nextLetterProbabilities(prefix: String): FloatArray? {
        calls++
        longestPrefix = maxOf(longestPrefix, prefix.length)
        val started = System.nanoTime()
        return try { actual.nextLetterProbabilities(prefix) } finally { nanos += System.nanoTime() - started }
    }
}

/** Inspect exact production choices, without reproducing Gaussian constants. */
private fun spatialHypotheses(tracker: PinyinMultiPathTracker, index: Int): List<Map<String, Any>> {
    fun read(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(owner)
    }
    val contacts = read(tracker, "contacts") as List<*>
    require(index < contacts.size) { "Production tracker rejected verified replay history" }
    val choices = read(contacts[index]!!, "choices") as List<*>
    return choices.map { choice ->
        val letter = read(choice!!, "letter") as Char
        val logProbability = (read(choice, "logSpatialProbability") as Number).toDouble()
        linkedMapOf("letter" to letter.toString(), "log_probability" to logProbability,
            "probability" to exp(logProbability))
    }
}

fun main(args: Array<String>) {
    require(args.size == 5) { "INPUT MODEL OUTPUT WARMUP REPEATS" }
    val input = JsonParser.parseString(File(args[0]).readText()).asJsonObject
    val actual = File(args[1]).reader().use { PinyinTouchLanguageModel(it) }
    val gson = GsonBuilder().serializeNulls().create()
    val warmups = args[3].toInt()
    val repeats = args[4].toInt()
    val allTrials = input.getAsJsonArray("trials").map { trialElement ->
        val trial = trialElement.asJsonObject
        val taps = trial.getAsJsonArray("taps").map { tapElement ->
            val tap = tapElement.asJsonObject
            val cells = tap.getAsJsonArray("cells").map { cellElement ->
                val cell = cellElement.asJsonObject
                KeyCell(cell["key"].asString.single(), cell["left"].asFloat, cell["top"].asFloat,
                    cell["right"].asFloat, cell["bottom"].asFloat)
            }
            PinyinTapEvidence(TapEvidence(tap["original"].asString.single(), tap["down_x"].asFloat,
                tap["down_y"].asFloat, tap["density"].asFloat), cells)
        }
        val results = List(taps.size) { linkedMapOf<String, Any?>("index" to it,
            "record_ns" to mutableListOf<Long>(), "model_ns" to mutableListOf<Long>(),
            "model_calls" to mutableListOf<Int>(), "longest_model_prefix" to mutableListOf<Int>()) }
        var firstSignature: String? = null
        for (run in -warmups until repeats) {
            val model = CountingModel(actual)
            val tracker = PinyinMultiPathTracker(model, actual.syllables)
            val identity = Any()
            var raw = ""
            val signature = StringBuilder()
            for ((index, evidence) in taps.withIndex()) {
                val action = tracker.nextAction()
                model.reset()
                val after = raw + evidence.tap.original
                val started = System.nanoTime()
                val proposal = tracker.recordTap(action, identity, raw, raw.length, after, after.length, evidence)
                val elapsed = System.nanoTime() - started
                // This explanatory one-key decision is separate from the tracked whole spelling.
                // It is outside the measured tracker operation and never changes its raw letters.
                val decision = PinyinSpatialKeyDecider(actual).decide(evidence.tap, evidence.cells, raw)
                val stats = tracker.javaClass.methods.firstOrNull {
                    it.name == "getEvaluationStats" && it.parameterCount == 0
                }?.invoke(tracker)
                val statsTree = stats?.let { gson.toJsonTree(it) }
                signature.append(proposal?.alternativeSpelling ?: "-").append(';')
                if (run >= 0) {
                    val result = results[index]
                    @Suppress("UNCHECKED_CAST")
                    (result["record_ns"] as MutableList<Long>).add(elapsed)
                    @Suppress("UNCHECKED_CAST")
                    (result["model_ns"] as MutableList<Long>).add(model.nanos)
                    @Suppress("UNCHECKED_CAST")
                    (result["model_calls"] as MutableList<Int>).add(model.calls)
                    @Suppress("UNCHECKED_CAST")
                    (result["longest_model_prefix"] as MutableList<Int>).add(model.longestPrefix)
                    result["original_spelling"] = tracker.rawSpelling
                    result["proposal"] = proposal
                    result["production_spatial_hypotheses"] = spatialHypotheses(tracker, index)
                    result["single_key_explanation"] = decision
                    result["evaluation_stats"] = statsTree
                    result["rejection_reason"] = when {
                        proposal != null -> null
                        statsTree?.isJsonObject == true -> statsTree.asJsonObject["reason"]?.asString
                            ?: "not_exposed_by_source"
                        else -> "not_exposed_by_source"
                    }
                }
                raw = after
            }
            if (firstSignature == null) firstSignature = signature.toString()
            check(firstSignature == signature.toString()) { "Replay changed between identical runs" }
        }
        linkedMapOf<String, Any?>("ordinal" to trial["ordinal"].asInt,
            "steps" to results, "literal_spelling" to taps.joinToString("") { it.tap.original.toString() })
    }
    File(args[2]).writeText(gson.toJson(linkedMapOf("trials" to allTrials)) + "\n")
}
