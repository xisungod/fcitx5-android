/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.fcitx.fcitx5.android.input.keyboard.typing.KeyCell

data class TypingTestPrompt(val id: Int, val text: String, val pinyin: String)

/** Fixed, prescribed full-pinyin prompts. v represents ü; no tones or punctuation. */
object TypingTestPrompts {
    val all: List<TypingTestPrompt> = listOf(
        TypingTestPrompt(1, "你好啊", "nihaoa"),
        TypingTestPrompt(2, "小姑娘", "xiaoguniang"),
        TypingTestPrompt(3, "经常会", "jingchanghui"),
        TypingTestPrompt(4, "今天天气很好", "jintiantianqihenhao"),
        TypingTestPrompt(5, "我正在打字", "wozhengzaidazi"),
        TypingTestPrompt(6, "明天一起吃饭", "mingtianyiqichifan"),
        TypingTestPrompt(7, "请帮我看一下", "qingbangwokanyixia"),
        TypingTestPrompt(8, "这个问题怎么解决", "zhegewentizenmejiejue"),
        TypingTestPrompt(9, "晚上早点休息", "wanshangzaodianxiuxi"),
        TypingTestPrompt(10, "你现在在哪里", "nixianzainali"),
        TypingTestPrompt(11, "我们一起回家", "womenyiqihuijia"),
        TypingTestPrompt(12, "手机键盘很好用", "shoujijianpanhenhaoyong"),
        TypingTestPrompt(13, "输入速度越来越快", "shurusuduyuelaiyuekuai"),
        TypingTestPrompt(14, "今天工作很顺利", "jintiangongzuohenshunli"),
        TypingTestPrompt(15, "请给我发个消息", "qinggeiwofagexiaoxi"),
        TypingTestPrompt(16, "我马上就到了", "womashangjiudaole"),
        TypingTestPrompt(17, "周末出去散步", "zhoumochuqusanbu"),
        TypingTestPrompt(18, "绿色的旅行", "lvsedelvxing"),
        TypingTestPrompt(19, "保持自然的速度", "baochizirandesudu"),
        TypingTestPrompt(20, "祝你每天都开心", "zhunimeitiandoukaixin")
    )

    fun byId(id: Int): TypingTestPrompt? = all.firstOrNull { it.id == id }
}

enum class TypingTestInputKind { FULL_PINYIN, SHORTHAND_OR_MIXED, EXTERNAL, UNKNOWN }

/** Actual observed durations. OfferReady overlaps processing blocks; samples are not additive. */
enum class TypingTestStage { SendKey, TouchSearch, AlternativeQuery, OfferReady }

data class TypingTestStageTimings(
    val sendKeyNanos: List<Long> = emptyList(),
    val touchSearchNanos: List<Long> = emptyList(),
    val alternativeQueryNanos: List<Long> = emptyList(),
    /** Original triggering action enqueue -> published offer, including debounce/queues. */
    val offerReadyNanos: List<Long> = emptyList()
)

enum class TypingTestAlternativeEventKind { Generated, Rejected, Published, Displayed, Selected, Resolved }

/** Frozen evidence for one offer. A later commit must not retroactively change its eligibility. */
data class TypingTestAlternativeEvent(
    val offerToken: Long,
    val kind: TypingTestAlternativeEventKind,
    val originalPinyin: String? = null,
    val alternativePinyin: String? = null,
    val candidateText: String? = null,
    val reason: String? = null,
    val success: Boolean? = null,
    val fullPromptComposition: Boolean = false,
    /** Native Rime order, zero-based; null if unavailable. Never a calibrated confidence. */
    val originalRank: Int? = null,
    /** Number of bounded spelling paths examined; explanatory evidence, not a model score. */
    val searchPathCount: Int? = null
)

/** Freeze at the FIRST complete full-pinyin attempt, before any correction/commit. */
data class TypingTestCandidateSnapshot(
    val rawPinyin: String,
    val candidates: List<String>,
    val completePromptComposition: Boolean = true,
    val coherent: Boolean = true
)

/** DOWN in unanimated key coordinates. This is observed evidence, never a corrected letter. */
data class TypingTestTouch(
    val originalKey: Char,
    val downX: Float,
    val downY: Float,
    val density: Float,
    val cells: List<KeyCell>,
    val orientation: String = "unknown",
    val hand: String = "unknown"
)

data class TypingTestTrialInput(
    val prompt: TypingTestPrompt,
    val inputKind: TypingTestInputKind,
    val firstAttemptPinyin: String? = null,
    /** False for a prefix frozen by an early backspace/commit. Never score its untouched suffix. */
    val firstAttemptComplete: Boolean = false,
    val firstAttemptTouches: List<TypingTestTouch> = emptyList(),
    val finalInputPinyin: String? = null,
    /** Independently observed raw spelling at the first-complete candidate snapshot generation. */
    val observedAttemptPinyin: String? = null,
    val finalCandidateSnapshot: TypingTestCandidateSnapshot? = null,
    val committedText: String = "",
    val backspaceCount: Int = 0,
    val letterKeyCount: Int = 0,
    /** Enqueued letter action -> complete callback; detached search/probes are separate samples. */
    val processingNanos: List<Long> = emptyList(),
    val priorCommitCount: Int = 0,
    /** First complete candidate spelling; finalInputPinyin can differ after the user repairs it. */
    val candidateInputPinyin: String? = finalInputPinyin,
    val stageTimings: TypingTestStageTimings = TypingTestStageTimings(),
    val alternativeEvents: List<TypingTestAlternativeEvent> = emptyList()
)

data class TypingTestFraction(val numerator: Int, val denominator: Int) {
    val value: Double? get() = if (denominator > 0) numerator.toDouble() / denominator else null
}

data class TypingTestLatency(
    val sampleCount: Int,
    val p50Nanos: Long?,
    val p95Nanos: Long?,
    val maximumNanos: Long?,
    val invalidSampleCount: Int,
    val omittedSampleCount: Int,
    val measurement: String = "enqueue_to_key_action_completion_ns"
)

data class TypingTestStageLatencies(
    val sendKey: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0, "send_key_ns"),
    val touchSearch: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0, "touch_search_ns"),
    val alternativeQuery: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0,
        "alternative_query_ns"),
    val offerReady: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0,
        "enqueue_to_offer_publish_ns")
)

/** Event counts describe the pipeline; target hit rates only describe eligible prescribed phrases. */
data class TypingTestAlternativeMetrics(
    val generatedCount: Int = 0,
    val rejectedCount: Int = 0,
    val publishedCount: Int = 0,
    val displayedCount: Int = 0,
    val selectedCount: Int = 0,
    val resolvedCount: Int = 0,
    val resolvedSuccessCount: Int = 0,
    val rejectionReasons: Map<String, Int> = emptyMap(),
    val publishedHitRate: TypingTestFraction = TypingTestFraction(0, 0),
    val selectionSuccessRate: TypingTestFraction = TypingTestFraction(0, 0),
    val targetSelectionRate: TypingTestFraction = TypingTestFraction(0, 0)
)

/** Proposed labels for a prescribed, successfully completed trial; not automatic training. */
data class TypingTestCalibrationSample(
    val inputIndex: Int,
    val originalKey: Char,
    val intendedKey: Char,
    val layoutSignature: String,
    val orientation: String,
    val hand: String,
    val normalizedOffsetX: Double,
    val normalizedOffsetY: Double
)

data class TypingTestTrialResult(
    val promptId: Int,
    val inputKind: TypingTestInputKind,
    val targetCompleted: Boolean,
    val firstAttemptPinyin: String?,
    val finalInputPinyin: String?,
    val rawEditRate: TypingTestFraction,
    val adjacentSubstitutionRate: TypingTestFraction,
    val top1HitRate: TypingTestFraction,
    val top3HitRate: TypingTestFraction,
    val backspaceRate: TypingTestFraction,
    val backspaceCount: Int,
    val letterKeyCount: Int,
    val alignment: TypingTestAlignment?,
    val unscoredReasons: List<String>,
    val calibrationSamples: List<TypingTestCalibrationSample>,
    val latency: TypingTestLatency,
    val stageLatencies: TypingTestStageLatencies = TypingTestStageLatencies(),
    val alternativeMetrics: TypingTestAlternativeMetrics = TypingTestAlternativeMetrics()
)
