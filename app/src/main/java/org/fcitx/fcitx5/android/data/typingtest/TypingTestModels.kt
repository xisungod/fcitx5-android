/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.fcitx.fcitx5.android.input.keyboard.typing.KeyCell

data class TypingTestPrompt(val id: Int, val text: String, val pinyin: String,
    /** Evaluation only: one prescribed syllable per Unicode code point. Never decoder input. */
    val pinyinSyllables: List<String> = emptyList())

/** Fixed, prescribed full-pinyin prompts. v represents ü; no tones or punctuation. */
object TypingTestPrompts {
    val all: List<TypingTestPrompt> = listOf(
        TypingTestPrompt(1, "你好啊", "nihaoa", listOf("ni", "hao", "a")),
        TypingTestPrompt(2, "小姑娘", "xiaoguniang", listOf("xiao", "gu", "niang")),
        TypingTestPrompt(3, "经常会", "jingchanghui", listOf("jing", "chang", "hui")),
        TypingTestPrompt(4, "今天天气很好", "jintiantianqihenhao", listOf("jin", "tian", "tian", "qi", "hen", "hao")),
        TypingTestPrompt(5, "我正在打字", "wozhengzaidazi", listOf("wo", "zheng", "zai", "da", "zi")),
        TypingTestPrompt(6, "明天一起吃饭", "mingtianyiqichifan", listOf("ming", "tian", "yi", "qi", "chi", "fan")),
        TypingTestPrompt(7, "请帮我看一下", "qingbangwokanyixia", listOf("qing", "bang", "wo", "kan", "yi", "xia")),
        TypingTestPrompt(8, "这个问题怎么解决", "zhegewentizenmejiejue", listOf("zhe", "ge", "wen", "ti", "zen", "me", "jie", "jue")),
        TypingTestPrompt(9, "晚上早点休息", "wanshangzaodianxiuxi", listOf("wan", "shang", "zao", "dian", "xiu", "xi")),
        TypingTestPrompt(10, "你现在在哪里", "nixianzaizainali", listOf("ni", "xian", "zai", "zai", "na", "li")),
        TypingTestPrompt(11, "我们一起回家", "womenyiqihuijia", listOf("wo", "men", "yi", "qi", "hui", "jia")),
        TypingTestPrompt(12, "手机键盘很好用", "shoujijianpanhenhaoyong", listOf("shou", "ji", "jian", "pan", "hen", "hao", "yong")),
        TypingTestPrompt(13, "输入速度越来越快", "shurusuduyuelaiyuekuai", listOf("shu", "ru", "su", "du", "yue", "lai", "yue", "kuai")),
        TypingTestPrompt(14, "今天工作很顺利", "jintiangongzuohenshunli", listOf("jin", "tian", "gong", "zuo", "hen", "shun", "li")),
        TypingTestPrompt(15, "请给我发个消息", "qinggeiwofagexiaoxi", listOf("qing", "gei", "wo", "fa", "ge", "xiao", "xi")),
        TypingTestPrompt(16, "我马上就到了", "womashangjiudaole", listOf("wo", "ma", "shang", "jiu", "dao", "le")),
        TypingTestPrompt(17, "周末出去散步", "zhoumochuqusanbu", listOf("zhou", "mo", "chu", "qu", "san", "bu")),
        TypingTestPrompt(18, "绿色的旅行", "lvsedelvxing", listOf("lv", "se", "de", "lv", "xing")),
        TypingTestPrompt(19, "保持自然的速度", "baochizirandesudu", listOf("bao", "chi", "zi", "ran", "de", "su", "du")),
        TypingTestPrompt(20, "祝你每天都开心", "zhunimeitiandoukaixin", listOf("zhu", "ni", "mei", "tian", "dou", "kai", "xin"))
    )

    fun byId(id: Int): TypingTestPrompt? = all.firstOrNull { it.id == id }
}

enum class TypingTestInputKind { FULL_PINYIN, SHORTHAND_OR_MIXED, EXTERNAL, UNKNOWN }

/** Actual observed durations. OfferReady overlaps processing blocks; samples are not additive. */
enum class TypingTestStage {
    SendKey, TouchSearch, AlternativeQuery, OfferReady,
    ProbeLibraryLoad, ProbeInitialization, ProbeNativeQuery
}

data class TypingTestStageTimings(
    val sendKeyNanos: List<Long> = emptyList(),
    val touchSearchNanos: List<Long> = emptyList(),
    val alternativeQueryNanos: List<Long> = emptyList(),
    /** Original triggering action enqueue -> published offer, including debounce/queues. */
    val offerReadyNanos: List<Long> = emptyList(),
    val probeLibraryLoadNanos: List<Long> = emptyList(),
    val probeInitializationNanos: List<Long> = emptyList(),
    val probeNativeQueryNanos: List<Long> = emptyList()
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
    val searchPathCount: Int? = null,
    val inputKindAtCapture: TypingTestInputKind? = null
)

/** Freeze at the FIRST target-length full-pinyin observation, before any repair/commit. */
data class TypingTestCandidateSnapshot(
    val rawPinyin: String,
    val candidates: List<String>,
    val completePromptComposition: Boolean = true,
    val coherent: Boolean = true,
    /** Explicitly frozen in the observation callback; null preserves legacy input adapters. */
    val inputKindAtCapture: TypingTestInputKind? = null,
    val priorCommitCountAtCapture: Int? = null
)

/** DOWN in unanimated key coordinates. This is observed evidence, never a corrected letter. */
data class TypingTestTouch(
    val originalKey: Char,
    val downX: Float,
    val downY: Float,
    val density: Float,
    val cells: List<KeyCell>,
    val orientation: String = "unknown",
    val hand: String = "unknown",
    val contactId: Long? = null,
    val pointerId: Int? = null,
    val downTime: Long? = null,
    val downSequence: Long? = null,
    val dispatchSequence: Long? = null,
    val dispatchTime: Long? = null,
    val physicalUpTime: Long? = null,
    val physicalUpX: Float? = null,
    val physicalUpY: Float? = null
)

/** One completed query call, tied to its offer; cache reads are explicitly separate. */
data class TypingTestProbeQuery(
    val offerToken: Long,
    val elapsedNanos: Long,
    val libraryLoadNanos: Long,
    val initializationNanos: Long,
    val nativeQueryNanos: Long,
    val cacheHit: Boolean,
    val coldInitialization: Boolean,
    val available: Boolean,
    val withinBudget: Boolean,
    val nativeWithinBudget: Boolean,
    val failureReason: String? = null
)

data class TypingTestTrialInput(
    val prompt: TypingTestPrompt,
    val inputKind: TypingTestInputKind,
    val firstAttemptPinyin: String? = null,
    /** False for a prefix frozen by an early backspace/commit. Never score its untouched suffix. */
    val firstAttemptComplete: Boolean = false,
    val firstAttemptTouches: List<TypingTestTouch> = emptyList(),
    val finalInputPinyin: String? = null,
    /** Independently observed raw spelling at the first target-length snapshot generation. */
    val observedAttemptPinyin: String? = null,
    val finalCandidateSnapshot: TypingTestCandidateSnapshot? = null,
    val committedText: String = "",
    val backspaceCount: Int = 0,
    val letterKeyCount: Int = 0,
    /** Enqueued letter action -> complete callback; detached search/probes are separate samples. */
    val processingNanos: List<Long> = emptyList(),
    val priorCommitCount: Int = 0,
    /** First target-length candidate spelling; raw first attempt may include later extra letters. */
    val candidateInputPinyin: String? = finalInputPinyin,
    val stageTimings: TypingTestStageTimings = TypingTestStageTimings(),
    val alternativeEvents: List<TypingTestAlternativeEvent> = emptyList(),
    /** First-attempt qualification is frozen before repairs or later unsupported actions. */
    val firstAttemptInputKind: TypingTestInputKind? = null,
    val displayedCandidateSnapshot: TypingTestCandidateSnapshot? = null,
    /** Fail closed for final calibration confirmation, independent of first-attempt scoring. */
    val calibrationConfirmed: Boolean? = null,
    val probeQueries: List<TypingTestProbeQuery> = emptyList(),
    val firstAttemptOmittedLetterCount: Int = 0,
    /** Qualification of the raw stop-delimited window, separate from the earlier snapshot. */
    val rawAttemptInputKind: TypingTestInputKind? = null,
    val predictionCommits: List<TypingTestPredictionCommit> = emptyList(),
    val predictionEvents: List<TypingTestPredictionEvent> = emptyList(),
    val predictionQueries: List<TypingTestPredictionQuery> = emptyList(),
    val predictionOmittedRecordCount: Int = 0
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
        "enqueue_to_offer_publish_ns"),
    val probeLibraryLoad: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0,
        "probe_library_load_ns"),
    val probeInitialization: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0,
        "probe_initialization_ns"),
    val probeNativeQuery: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0,
        "probe_native_query_ns")
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
    val normalizedOffsetY: Double,
    /** Opaque per-trial confirmation receipt; it contains no entered text. */
    val confirmationId: String? = null
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
    val alternativeMetrics: TypingTestAlternativeMetrics = TypingTestAlternativeMetrics(),
    /** Prefix-only positional errors; never count the untouched target suffix as missing. */
    val earlyPrefixMismatchRate: TypingTestFraction = TypingTestFraction(0, 0),
    val earlyPrefixAdjacentRate: TypingTestFraction = TypingTestFraction(0, 0),
    val displayedTop1HitRate: TypingTestFraction = TypingTestFraction(0, 0),
    val displayedTop3HitRate: TypingTestFraction = TypingTestFraction(0, 0),
    val predictionMetrics: TypingTestPredictionMetrics = TypingTestPredictionMetrics()
)

/** Prediction observation contains no predictor context or candidate text. */
enum class TypingTestPredictionWarmth { Cold, Warm, Cache, Unknown }
enum class TypingTestPredictionEventKind { Published, Drawn, Selected, Resolved }

data class TypingTestPredictionCommit(
    val commitToken: Long,
    val success: Boolean,
    val committedAtNanos: Long,
    val scopeReliable: Boolean
)

data class TypingTestPredictionEvent(
    val commitToken: Long,
    val offerToken: Long,
    val kind: TypingTestPredictionEventKind,
    val warmth: TypingTestPredictionWarmth = TypingTestPredictionWarmth.Unknown,
    val visibleIndices: List<Int> = emptyList(),
    val selectedIndex: Int? = null,
    val success: Boolean? = null,
    /** Actual accepted editor commit -> first fully visible dispatchDraw, not a hardware frame. */
    val commitToDrawNanos: Long? = null,
    val targetMatched: Boolean? = null,
    /** Estimated pinyin LETTER keys avoided; not measured total tap savings. */
    val estimatedPinyinLetterKeys: Int? = null,
    val unscoredReason: String? = null
)

data class TypingTestPredictionQuery(
    val commitToken: Long,
    val outcome: String,
    val warmth: TypingTestPredictionWarmth,
    val available: Boolean? = null,
    val elapsedNanos: Long? = null,
    val initializationNanos: Long? = null,
    val queryNanos: Long? = null,
    /** Independent read-only completion index status; null means not observed. */
    val completionAvailable: Boolean? = null,
    val completionInitializationNanos: Long? = null,
    val completionFailureReason: String? = null
)

data class TypingTestPredictionLatencySample(
    val warmth: TypingTestPredictionWarmth,
    val elapsedNanos: Long
)

data class TypingTestPredictionMetrics(
    val successfulCommitCount: Int = 0,
    val failedCommitCount: Int = 0,
    val publishedCount: Int = 0,
    val drawnCount: Int = 0,
    val selectedCount: Int = 0,
    val adoptedCount: Int = 0,
    /** Distinct successful commit anchors with drawn offers / all successful anchors. */
    val commitDrawCoverage: TypingTestFraction = TypingTestFraction(0, 0),
    /** Successfully adopted offers with draw evidence / drawn offers. */
    val adoptionRate: TypingTestFraction = TypingTestFraction(0, 0),
    /** Adopted offers whose draw was observed / all successfully adopted offers. */
    val adoptionDrawCoverage: TypingTestFraction = TypingTestFraction(0, 0),
    val targetHitRate: TypingTestFraction = TypingTestFraction(0, 0),
    val targetScoringCoverage: TypingTestFraction = TypingTestFraction(0, 0),
    val savingsCoverage: TypingTestFraction = TypingTestFraction(0, 0),
    val estimatedPinyinLetterKeys: Int = 0,
    val unscoredReasons: Map<String, Int> = emptyMap(),
    val queryOutcomes: Map<String, Int> = emptyMap(),
    val drawLatency: TypingTestLatency = TypingTestLatency(0, null, null, null, 0, 0,
        "actual_commit_success_to_first_visible_dispatch_draw_ns"),
    val drawLatencyByWarmth: Map<TypingTestPredictionWarmth, TypingTestLatency> = emptyMap(),
    /** Retained real samples allow session percentiles, never averages of per-trial percentiles. */
    val drawLatencySamples: List<TypingTestPredictionLatencySample> = emptyList(),
    val queryRecords: List<TypingTestPredictionQuery> = emptyList(),
    val omittedRecordCount: Int = 0
)
