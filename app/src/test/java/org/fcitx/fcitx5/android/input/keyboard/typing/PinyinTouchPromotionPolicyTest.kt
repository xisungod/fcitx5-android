/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.min

class PinyinTouchPromotionPolicyTest {
    private val model by lazy {
        listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
            .reader().use(PinyinTouchLanguageModel::parse)
    }
    private val policy by lazy { PinyinTouchPromotionPolicy(model.syllables) }
    private val candidates = listOf("一人", "古人", "此人")

    private fun proposal(raw: String = "giren", alternative: String = "guren",
                         spatial: Double = .6, path: Double = 4.0): PinyinMultiPathProposal {
        val changes = raw.indices.filter { raw[it] != alternative[it] }
        return PinyinMultiPathProposal(raw, alternative, 2.0, 1.0, .95f, changes, 7, 3,
            PinyinTouchPromotionEvidence(path, changes.map {
                PinyinChangedContactSpatialEvidence(it, raw[it], alternative[it], spatial)
            }))
    }

    @Test fun `strict first slot can activate with independent spatial support and literal agreement`() {
        val decision = policy.evaluate(proposal(), "古人", candidates)
        assertEquals(PinyinTouchPromotionPolicy.Reason.Eligible, decision.reason)
        assertTrue(decision.promoteToFirst)
        assertTrue(policy.evaluate(proposal(), "古人", listOf("一人", "此人", "古人")).promoteToFirst)
    }

    @Test fun `correct full spelling wins even against extreme scores including screenshot and gaileme`() {
        val independentCorrect = listOf(
            "gaileme", "nihaoa", "jiben", "gongzuo", "xiaoguniang", "guren", "shang",
            "wozhengzaidazi", "zhoumochuqusanbu", "zhunimeitiandoukaixin", "beijing", "shanghai",
            "nanjing", "chongqing", "shenzhen", "guangzhou", "tianjin", "chengdu", "xian", "wuhan",
            "zaoshanghao", "wanshanghao", "xiexie", "zaijian", "duibuqi", "meiguanxi", "qingwen",
            "mingbai", "shijian", "shenghuo", "pengyou", "jiating", "xuexi", "gongsi", "xuexiao",
            "dianhua", "xinxi", "xihuan", "kuaile", "kaixin", "jiankang", "anquan", "fangbian",
            "women", "nimen", "tamen", "ziji", "xianzai", "jinlai", "shuru", "shuchu", "jianpan",
            "shouji", "diannao", "wangluo", "jintian", "mingtian", "zuotian", "meitian", "xiangqu",
            "ruguo", "yinwei", "suoyi", "dan shi".replace(" ", ""), "bukeqi", "keyi", "buyao",
            "qingchu", "yijing", "yihou", "yiqian", "xiuxi", "qingbangwokanyixia",
            "zhegewentizenmejiejue", "baochizirandesudu", "jintiangongzuohenshunli", "lvsedelvxing"
        )
        for (raw in independentCorrect) {
            val alternative = raw.replaceRange(0, 1, if (raw[0] == 'j') "k" else "j")
            val decision = policy.evaluate(proposal(raw, alternative, 100.0, 100.0),
                "其他", listOf("原词", "其他"))
            assertEquals("Literal full pinyin $raw", PinyinTouchPromotionPolicy.Reason.CompleteLiteralPinyin,
                decision.reason)
        }
        assertFalse(policy.evaluate(proposal("gaileme", "baileme", 100.0, 100.0),
            "饱了么", listOf("改了么", "饱了么")).promoteToFirst)
        assertFalse(policy.evaluate(proposal("nihaoa", "jihaoa", 100.0, 100.0),
            "几号啊", listOf("你好啊", "几号啊")).promoteToFirst)
    }

    @Test fun `full syllables plus initials remain valid protected input including zj and xh`() {
        for (raw in listOf("zj", "xh", "jch", "bj", "nijch", "jchnihao", "niwjintian",
            "wozjengzaidazi", "xhoumochuqusanbu", "jibgchsnghui")) {
            val alternative = raw.replaceRange(0, 1, if (raw[0] == 'z') "x" else "z")
            assertFalse(raw, policy.evaluate(proposal(raw, alternative, 100.0, 100.0), "其他",
                listOf("原词", "其他")).promoteToFirst)
        }
        assertEquals(PinyinTouchPromotionPolicy.Reason.ProtectedAbbreviation,
            policy.evaluate(proposal("wozjengzaidazi", "wozhengzaidazi"), "我正在打字",
                listOf("我自己而能够在打字", "我正在打字")).reason)
    }

    @Test fun `unfinished current syllables and unsupported spellings never promote`() {
        for (raw in listOf("jib", "jibe", "jingch", "changh", "xiaogu", "jintiang")) {
            val alternative = raw.replaceRange(raw.lastIndex, raw.length, if (raw.last() == 'n') "b" else "n")
            assertFalse(raw, policy.evaluate(proposal(raw, alternative), "其他", listOf("原词", "其他")).promoteToFirst)
        }
        for (raw in listOf("Jungchanghui", "jung'changhui", "jung changhui", "giren1")) {
            val alternative = raw.replaceRange(1, 2, "i")
            assertEquals(PinyinTouchPromotionPolicy.Reason.UnsupportedSpelling,
                policy.evaluate(proposal(raw, alternative), "古人", candidates).reason)
        }
    }

    @Test fun `mixed score confidence cannot substitute for missing or weaker spatial evidence`() {
        val original = proposal()
        assertEquals(PinyinTouchPromotionPolicy.Reason.MissingSpatialEvidence,
            policy.evaluate(original.copy(promotionEvidence = null, modelConfidence = 1f), "古人", candidates).reason)
        for (spatial in listOf(-3.0, 0.0, .29, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(PinyinTouchPromotionPolicy.Reason.InsufficientSpatialAdvantage,
                policy.evaluate(proposal(spatial = spatial, path = 100.0), "古人", candidates).reason)
        }
        for (path in listOf(0.0, 2.99, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(PinyinTouchPromotionPolicy.Reason.InsufficientPathAdvantage,
                policy.evaluate(proposal(path = path), "古人", candidates).reason)
        }
        assertEquals(PinyinTouchPromotionPolicy.Reason.MissingSpatialEvidence,
            policy.evaluate(original.copy(promotionEvidence = original.promotionEvidence!!.copy(
                changedContacts = listOf(PinyinChangedContactSpatialEvidence(2, 'u', 'i', 5.0)))),
                "古人", candidates).reason)
    }

    @Test fun `novel words far ranks partial alternatives and English are not first-slot evidence`() {
        for (rawWords in listOf(emptyList(), listOf("古人", "其他"), listOf("其他", "第三"),
            listOf("其他", "第三", "第四", "古人"))) {
            assertEquals(PinyinTouchPromotionPolicy.Reason.MissingLiteralAgreement,
                policy.evaluate(proposal(), "古人", rawWords).reason)
        }
        assertEquals(PinyinTouchPromotionPolicy.Reason.NonChineseCandidate,
            policy.evaluate(proposal(), "古人", listOf("jungle", "古人")).reason)
        assertEquals(PinyinTouchPromotionPolicy.Reason.NonChineseCandidate,
            policy.evaluate(proposal(), "jing", listOf("一人", "jing")).reason)
        assertEquals(PinyinTouchPromotionPolicy.Reason.AlternativeNotFullPinyin,
            policy.evaluate(proposal(alternative = "gireg"), "古人", candidates).reason)
    }

    private fun row(letters: String, left: Float, top: Float) = letters.mapIndexed { index, letter ->
        KeyCell(letter, left + index * 100f, top, left + (index + 1) * 100f, top + 140f)
    }
    private val cells = listOf(row("qwertyuiop", 0f, 0f), row("asdfghjkl", 50f, 140f),
        row("zxcvbnm", 150f, 280f)).flatten()

    private fun trackerProposal(offsets: Map<Char, CenterOffset>, center: Boolean = false): PinyinMultiPathProposal? {
        val tracker = PinyinMultiPathTracker(model, model.syllables)
        val raw = "giren"
        var result: PinyinMultiPathProposal? = null
        for (index in raw.indices) {
            val cell = cells.first { it.letter == raw[index] }
            val downX = if (index == 1 && !center) cell.left + .25f else cell.centerX
            val evidence = PinyinTapEvidence(TapEvidence(raw[index], downX, cell.centerY, 1f), cells)
            result = tracker.recordTap(tracker.nextAction(), this, raw.take(index), index,
                raw.take(index + 1), index + 1, evidence, offsets)
        }
        return result
    }

    @Test fun `real search attaches spatial evidence and uniform contacts stay supplementary`() {
        val uniform = trackerProposal(emptyMap())!!
        assertEquals("guren", uniform.alternativeSpelling)
        assertTrue(uniform.promotionEvidence!!.changedContacts.single().logAlternativeOverOriginal < 0.0)
        assertEquals(PinyinTouchPromotionPolicy.Reason.InsufficientSpatialAdvantage,
            policy.evaluate(uniform, "古人", candidates).reason)
        val calibrated = trackerProposal(mapOf('u' to CenterOffset(.12f, 0f), 'i' to CenterOffset(.12f, 0f)))!!
        assertEquals("guren", calibrated.alternativeSpelling)
        assertTrue(policy.evaluate(calibrated, "古人", candidates).promoteToFirst)
        assertNull(trackerProposal(mapOf('u' to CenterOffset(.12f, 0f), 'i' to CenterOffset(.12f, 0f)), center = true))
    }

    @Test fun `every directed shared-edge pair preserves complete literal spelling`() {
        fun neighbours(a: KeyCell, b: KeyCell): Boolean {
            val horizontal = (abs(a.right - b.left) < .001 || abs(b.right - a.left) < .001) &&
                min(a.bottom, b.bottom) > maxOf(a.top, b.top)
            val vertical = (abs(a.bottom - b.top) < .001 || abs(b.bottom - a.top) < .001) &&
                min(a.right, b.right) > maxOf(a.left, b.left)
            return horizontal || vertical
        }
        var directedPairs = 0
        for (a in cells) for (b in cells) {
            if (a == b || !neighbours(a, b)) continue
            directedPairs++
            val literal = model.syllables.firstOrNull { a.letter in it } ?: continue
            val position = literal.indexOf(a.letter)
            val alternative = literal.replaceRange(position, position + 1, b.letter.toString())
            assertEquals("${a.letter} -> ${b.letter} preserves $literal",
                PinyinTouchPromotionPolicy.Reason.CompleteLiteralPinyin,
                policy.evaluate(proposal(literal, alternative, 100.0, 100.0), "其他",
                    listOf("原词", "其他")).reason)
        }
        assertEquals("All 48 shared edges must participate in both directions", 96, directedPairs)
    }
}
