/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import org.fcitx.fcitx5.android.core.LibimeNextWordPredictor
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.io.File

class NextWordSuggestionBackendTest {
    private fun fixture(vararg rows: String): ByteArray =
        ("# AXiang next-word completions v1\n# entries: ${rows.size}\n" +
            "# source: public synthetic backend fixture\n" + rows.joinToString("\n", postfix = "\n"))
            .toByteArray()

    private fun fixtureV2(vararg rows: String): ByteArray =
        ("# AXiang next-word completions v2\n# entries: ${rows.size}\n" +
            "# source: public synthetic backend fixture; flag is lexical specificity, not probability\n" +
            rows.joinToString("\n", postfix = "\n")).toByteArray()

    private fun result(pool: List<String>) = LibimeNextWordPredictor.Result(
        pool.filter(NextWordPredictionRuntime::isHanText).take(5), 100L,
        queryNanos = 80L, suggestionPool = pool)

    // Direct libime N=32 pools from a public synthetic ARM64/bionic run; not handset logs,
    // neural model output, or a claimed measurement of phone/UI latency.
    // results.json SHA256 cd7631469cfb35459eaba8764e1834cb7f7f01ecd97c8b7827748553523f708f
    private val realDo = listOf("了", "的", "一", "个", "得", "过", "、", "。", "一些", "不", "什么", "准备", "出来", "生意", "着")
    private val realThanks = listOf("你", "。", "你们", "大家", "您", "！", "、", "了", "我", "的", "，")
    private val realHello = listOf("吗", "的", "不", "了", "。", "，", "呢", "、", "吧", "得", "一", "几", "在", "多", "！")

    @Test fun realDoPoolMakesWhatAccessibleWithoutHardcodingTheContextOrRemovingParticles() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(realDo) }, { fixture("做\t好,饭\t一个,什么,作业") })
        val output = backend.query("做")
        assertEquals("了", output.candidates.first())
        assertTrue(output.candidates.indexOf("什么") in 0..2)
        assertTrue(output.candidates.contains("的"))
        assertFalse(output.candidates.contains("一些"))
        assertFalse(output.candidates.contains("做什么"))
        assertEquals(true, output.completionAvailable)
    }

    @Test fun realHelloAndThanksRetainTheirNaturalFirstCharacter() {
        val backend = NextWordSuggestionBackend({ context, _ -> result(if (context == "你好") realHello else realThanks) },
            { fixture("做\t饭\t什么", "谢谢\t你,您\t合作,大家") })
        assertEquals("吗", backend.query("你好").candidates.first())
        val thanks = backend.query("谢谢").candidates
        assertEquals("你", thanks.first())
        assertTrue(thanks.contains("你们"))
        assertTrue(thanks.contains("大家"))
        assertTrue(thanks.contains("您"))
    }

    @Test fun moreSpecificActualSegmentationPoolWinsOverTheLastCharacterFrequency() {
        // variants.json SHA256 be6733c0466802967998621b7e883c8dcd6e4a59c01e1e6c44aefb11fa7fcccd
        // This is the actual model pool for [你, 在, 做], not a fabricated probability.
        val contextPool = listOf("什么", "的", "一", "了", "着", "一些", "生意", "准备", "过", "不", "个", "得", "、", "。", "出来")
        val backend = NextWordSuggestionBackend({ _, _ -> result(contextPool) },
            { fixture("做\t好,到\t一个,出了,什么,到了,生意,作业") })
        val output = backend.query("你在做").candidates
        assertEquals(listOf("什么", "的"), output.take(2))
        assertTrue(output.contains("生意"))
    }

    @Test fun longestPrefixContinuationIsNotReplacedByTheCommonFinalCharacter() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(listOf("的", "了")) },
            { fixture("做\t好\t什么,生意", "想做\t好\t作业,工作") })
        val output = backend.query("我想做").candidates
        assertEquals(listOf("的", "作业", "工作"), output.take(3))
        assertTrue(output.contains("了"))
    }

    @Test fun anEmptyLongPrefixPhraseGroupCannotBeFilledBySplittingItsFinalCharacter() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(emptyList()) },
            { fixture("你好\t吗,啊\t", "好\t的,友\t评度,朋友") })
        assertEquals(listOf("吗", "啊"), backend.query("你好").candidates)
    }

    @Test fun unmatchedLongContextDoesNotBecomeItsLastCharacterButNativeContinuationsSurvive() {
        val drink = listOf("的", "一", "了", "茶", "咖啡", "杯", "完", "得", "着", "下", "不", "到", "啤酒", "多")
        val backend = NextWordSuggestionBackend({ context, _ -> result(if (context == "再见") listOf("了", "吧", "的") else drink) },
            { fixture("喝\t茶\t饮料,牛奶", "见\t面\t面礼,证人") })
        assertEquals(listOf("了", "吧", "的"), backend.query("再见").candidates)
        val values = backend.query("我想喝").candidates
        assertTrue(values.contains("茶"))
        assertTrue(values.contains("咖啡"))
        assertTrue(values.contains("啤酒"))
        assertFalse(values.contains("饮料"))
        assertFalse(values.contains("牛奶"))
    }

    @Test fun optionalAssetLossStillUsesTheFullRealModelPool() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(realDo) }, { throw FileNotFoundException() })
        val output = backend.query("做")
        assertTrue(output.available)
        assertEquals(false, output.completionAvailable)
        assertEquals("CompletionIndexMissing", output.completionFailureReason)
        assertTrue(output.candidates.indexOf("什么") in 0..2)
        assertEquals(realDo, output.suggestionPool)
    }

    @Test fun corruptOptionalAssetCannotDisableModelPredictionsOrOverwriteNativeFailure() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(realHello) }, { "broken".toByteArray() })
        val output = backend.query("你好")
        assertEquals("吗", output.candidates.first())
        assertEquals(false, output.completionAvailable)
        assertEquals("CompletionIndexInvalid", output.completionFailureReason)
        assertNull(output.failureReason)
    }

    @Test fun assetLoadsOnceAndColdInitializationIsSeparateFromNativeQueryTiming() {
        var time = 0L
        var loads = 0
        val backend = NextWordSuggestionBackend({ _, _ -> time += 30; result(realDo).copy(queryNanos = 20) },
            { loads++; time += 50; fixture("做\t饭\t什么") }, { time })
        val first = backend.query("做")
        assertEquals(80L, first.elapsedNanos)
        assertEquals(50L, first.completionInitializationNanos)
        assertEquals(20L, first.queryNanos)
        assertTrue(first.coldInitialization)
        val second = backend.query("做")
        assertEquals(30L, second.elapsedNanos)
        assertEquals(0L, second.completionInitializationNanos)
        assertFalse(second.coldInitialization)
        assertEquals(1, loads)
    }

    @Test fun failedAssetLoadIsNotRepeatedOnEveryCommit() {
        var loads = 0
        val backend = NextWordSuggestionBackend({ _, _ -> result(realDo) }, { loads++; throw FileNotFoundException() })
        backend.query("做")
        backend.query("做")
        assertEquals(1, loads)
    }

    @Test fun unavailableNativeModelRemainsUnavailableWithoutReadingTheOptionalIndex() {
        var loads = 0
        val backend = NextWordSuggestionBackend({ _, _ -> result(emptyList()).copy(available = false, failureReason = "ModelFilesUnavailable") },
            { loads++; fixture("做\t饭\t什么") })
        val output = backend.query("做")
        assertFalse(output.available)
        assertEquals("ModelFilesUnavailable", output.failureReason)
        assertNull(output.completionAvailable)
        assertEquals(0, loads)
    }

    @Test fun emptyInvalidAndPunctuationEndedContextsCannotLoadOrManufactureAnIndexOffer() {
        var loads = 0
        val backend = NextWordSuggestionBackend({ _, _ -> result(emptyList()) }, { loads++; fixture("做\t饭\t什么") })
        for (context in listOf("", "abc", "做！", "做😊", "做\uD800")) assertTrue(backend.query(context).candidates.isEmpty())
        assertTrue(backend.query("做", 0).candidates.isEmpty())
        assertEquals(0, loads)
    }

    @Test fun olderModelFixturesWithoutANewPoolAndGenericOnlyPredictionsStillWork() {
        val backend = NextWordSuggestionBackend({ _, _ -> LibimeNextWordPredictor.Result(listOf("的", "了", "一个"), 1) },
            { fixture("做\t好\t一些,一个,一点") })
        val output = backend.query("做").candidates
        assertEquals("的", output.first())
        assertTrue(output.contains("了"))
        assertTrue(output.contains("一个"))
        assertTrue(output.size <= 5)
    }

    @Test fun displayLimitsDuplicatesAndForbiddenOutputAreEnforcedAfterMerging() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(listOf("的", "什么", "什么", "abc", "作业", "！")) },
            { fixture("做\t饭\t什么,作业,工作") })
        for (limit in 1..8) {
            val output = backend.query("做", limit).candidates
            assertTrue(output.size <= limit)
            assertEquals(output.distinct(), output)
            assertTrue(output.all(NextWordPredictionRuntime::isHanText))
        }
    }

    @Test fun modelEmptyKnownPrefixAddsOnlyTheSuffixAndKeepsGenericFallbackUsable() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(emptyList()) },
            { fixture("做\t饭\t一个,什么,作业") })
        val output = backend.query("做").candidates
        assertTrue(output.contains("什么"))
        assertTrue(output.contains("饭"))
        assertTrue(output.contains("一个"))
        assertFalse(output.contains("做什么"))
    }

    @Test fun aSpecificLexicalRouteMayPrecedeGenericParticlesWhileTwoNativeOptionsSurvive() {
        val native = listOf("的", "了", "茶", "咖啡", "一")
        val backend = NextWordSuggestionBackend({ _, _ -> result(native) },
            { fixtureV2("字典前缀\t字\t可信续写,另一续写\t1") })
        val values = backend.query("字典前缀").candidates
        assertEquals(listOf("可信续写", "的", "了"), values.take(3))
        assertTrue(values.contains("茶"))
        assertEquals(5, values.size)
        assertFalse(values.contains("字典前缀可信续写"))
    }

    @Test fun ordinaryBroadRoutesAndOlderAssetsCannotClaimSpecificityByTheirLength() {
        for (bytes in listOf(fixtureV2("字典前缀\t字\t普通续写,另一续写\t0"), fixture("前缀\t字\t普通续写,另一续写"))) {
            val backend = NextWordSuggestionBackend({ _, _ -> result(listOf("的", "了", "咖啡")) }, { bytes })
            assertEquals("的", backend.query("字典前缀").candidates.first())
        }
    }

    @Test fun aSpecificRouteCannotReplaceExistingContentQuestionsOrPronouns() {
        for (first in listOf("吗", "你", "茶", "咖啡", "去", "不")) {
            val backend = NextWordSuggestionBackend({ _, _ -> result(listOf(first, "的", "了")) },
                { fixtureV2("字典前缀\t字\t可信续写,另一续写\t1") })
            assertEquals(first, backend.query("字典前缀").candidates.first())
        }
    }

    @Test fun aTinyDisplayCannotPromoteAtTheCostOfBothNativeFallbacks() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(listOf("的", "了")) },
            { fixtureV2("字典前缀\t字\t可信续写\t1") })
        assertEquals("的", backend.query("字典前缀", 1).candidates.first())
        assertEquals("的", backend.query("字典前缀", 2).candidates.first())
        assertEquals(listOf("可信续写", "的", "了"), backend.query("字典前缀", 3).candidates)
    }

    @Test fun aGenericPhraseCannotUseSpecificityToConsumeTheInformativePromotionSlot() {
        val backend = NextWordSuggestionBackend({ _, _ -> result(listOf("的", "了")) },
            { fixtureV2("字典前缀\t字\t一个,一些,可信续写\t1") })
        assertEquals("可信续写", backend.query("字典前缀").candidates.first())
    }

    @Test fun realThreeSourceLexicalEvidenceCanGuideGenericModelFixturesWithoutWordSpecificCode() {
        val relative = "src/main/assets/typing/next_word_completions.tsv"
        val asset = File(relative).takeIf(File::isFile) ?: File("app/$relative")
        val backend = NextWordSuggestionBackend({ _, _ -> result(listOf("的", "了", "在")) }, { asset.readBytes() })
        for ((prefix, suffix) in listOf("天青色" to "等烟雨", "一帆" to "风顺")) {
            val values = backend.query(prefix).candidates
            assertEquals(suffix, values.first())
            assertEquals(listOf("的", "了"), values.drop(1).take(2))
            assertFalse(values.contains(prefix + suffix))
        }
        val protected = NextWordSuggestionBackend({ _, _ -> result(listOf("咖啡", "的", "了")) }, { asset.readBytes() })
        assertEquals("咖啡", protected.query("天青色").candidates.first())
    }

    @Test fun actualArmPoolsAndActualAssetKeepContentAcrossDifferentContexts() {
        // Public synthetic queries against the actual new ARM64/bionic JNI bridge. These are
        // bounded native model pools, not measured user intentions or phone/UI accuracy.
        // Bridge SHA256 1eccc073ba9652a58e8765b355cb8e4326479247895465740d7f223ce08434f0
        val pools = linkedMapOf(
            "做" to listOf("了", "的", "一", "个", "得", "过", "一些", "不", "什么", "准备", "出来", "生意", "着"),
            "我想做" to listOf("的", "一", "什么", "个", "了", "一些", "得", "生意", "不", "过", "准备", "出来", "着"),
            "你在做" to listOf("什么", "的", "一", "了", "着", "一些", "生意", "准备", "过", "不", "个", "得", "出来"),
            "吃" to listOf("了", "的", "一", "不", "什么", "得", "着", "起来", "过", "东西", "个", "到"),
            "我想喝" to listOf("的", "一", "了", "茶", "咖啡", "杯", "完", "得", "着", "下", "不", "到", "啤酒", "多"),
            "去" to listOf("了", "找", "的", "看", "做", "一", "买", "医院", "参加", "吃", "吧", "和"),
            "学习" to listOf("的", "了", "和", "一", "与", "中", "成绩", "是", "目标", "经历", "上"),
            "今天要" to listOf("去", "和", "做", "不", "在", "比", "把", "好", "想", "有", "注意", "用", "的"),
            "今天" to listOf("就", "是", "的", "上午", "下午", "也", "又", "在", "我", "我们", "早上", "要"),
            "谢谢" to listOf("你", "你们", "大家", "您", "了", "我", "的"),
            "你好" to listOf("吗", "的", "不", "了", "呢", "吧", "得", "一", "几", "在", "多"),
            "联系" to listOf("电话", "了", "到", "在", "在一起", "方式", "的", "起来", "上", "不", "地址", "我们"),
            "怎么" to listOf("也", "了", "会", "做", "办", "可能", "就", "看", "能", "说", "不", "去", "可以", "回", "来"),
            "想" to listOf("了", "要", "不", "做", "去", "和", "在", "把", "的", "着", "让", "说", "一"))
        val relative = "src/main/assets/typing/next_word_completions.tsv"
        val asset = File(relative).takeIf(File::isFile) ?: File("app/$relative")
        val backend = NextWordSuggestionBackend({ context, _ -> result(pools.getValue(context)) }, { asset.readBytes() })
        val actualIndex = NextWordCompletionIndex.parse(asset.readBytes())
        val outputs = pools.mapValues { (context, _) -> backend.query(context).candidates }
        for ((context, values) in outputs) {
            assertTrue("Original model fallback survives for $context", values.contains(pools.getValue(context).first()))
            assertEquals(values.distinct(), values)
            assertTrue(values.size <= 5)
            println("PUBLIC_PREDICTION_MATRIX " + context + "\t" + pools.getValue(context).joinToString(",") + "\t" + values.joinToString(","))
        }
        assertTrue(outputs.getValue("我想喝").contains("茶"))
        assertTrue(outputs.getValue("我想喝").contains("咖啡"))
        assertTrue(outputs.getValue("去").contains("找"))
        assertTrue(outputs.getValue("去").contains("医院"))
        assertTrue(outputs.getValue("学习").contains("成绩"))
        assertTrue(outputs.getValue("学习").contains("目标"))
        assertTrue(outputs.getValue("今天").contains("上午"))
        assertTrue(outputs.getValue("今天").contains("下午"))
        assertTrue(outputs.getValue("谢谢").contains("您"))
        assertTrue(outputs.getValue("你好").contains("吗"))
        for (context in listOf("你好", "谢谢", "你在做", "今天要", "联系")) {
            assertEquals("Specificity cannot override existing content for $context",
                pools.getValue(context).first(), outputs.getValue(context).first())
        }
        assertTrue(outputs.getValue("今天要").contains("去"))
        assertTrue(outputs.getValue("做").indexOf("什么") in 0..2)
        for (context in listOf("我想喝", "去", "学习", "今天要", "今天", "谢谢", "你好", "联系", "怎么", "想")) {
            if (outputs.getValue(context).contains("什么")) {
                val literal = actualIndex.trailingMatches(context).firstOrNull()!!
                assertTrue("A continuation must have actual literal evidence rather than a global override for $context",
                    literal.multis.contains("什么"))
            }
        }
        assertNotEquals(outputs.getValue("做"), outputs.getValue("我想喝"))
        assertNotEquals(outputs.getValue("去"), outputs.getValue("学习"))
    }
}
