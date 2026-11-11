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
                         spatial: Double = -1.0, path: Double = 5.0): PinyinMultiPathProposal {
        val changes = raw.indices.filter { raw[it] != alternative[it] }
        return PinyinMultiPathProposal(raw, alternative, 2.0, 1.0, .95f, changes, 7, 3,
            PinyinTouchPromotionEvidence(path, changes.map {
                PinyinChangedContactSpatialEvidence(it, raw[it], alternative[it], spatial)
            }))
    }

    @Test fun `letters that are not pinyin yield the first slot to a nearby-key spelling without calibration`() {
        // The tap was inside the literal key (negative log ratio): no personal offsets involved.
        val decision = policy.evaluate(proposal(), "古人", candidates)
        assertEquals(PinyinTouchPromotionPolicy.Reason.Eligible, decision.reason)
        assertTrue(decision.promoteToFirst)
        // The word need not already be among the literal candidates.
        for (rawWords in listOf(listOf("一人", "此人", "古人"), listOf("其他", "第三"),
            listOf("其他", "第三", "第四", "古人"), listOf("其他"))) {
            assertTrue(rawWords.toString(), policy.evaluate(proposal(), "古人", rawWords).promoteToFirst)
        }
        assertTrue(policy.evaluate(proposal("nihso", "nihao", spatial = -1.2), "你好",
            listOf("你和所哦")).promoteToFirst)
        assertTrue(policy.evaluate(proposal("jibgchsnghui", "jingchanghui", spatial = -.3), "经常会",
            listOf("机兵工厂商会")).promoteToFirst)
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

    @Test fun `initials and unfinished endings are never overridden however strong the scores`() {
        for ((raw, alternative) in listOf("zj" to "zu", "xh" to "xu", "bj" to "bu", "jch" to "jue",
            "nijch" to "nijiu", "jingch" to "jingcu", "changh" to "changu", "wom" to "won")) {
            val decision = policy.evaluate(proposal(raw, alternative, 0.0, 100.0), "其他", listOf("原词", "其他"))
            assertEquals(raw, PinyinTouchPromotionPolicy.Reason.ProtectedIncompletePrefix, decision.reason)
        }
    }

    @Test fun `letters that also read as syllables plus initials need a tap close to the border`() {
        for ((raw, alternative) in listOf("xhuan" to "shuan", "zhge" to "zhue",
            "wozjengzaidazi" to "wozhengzaidazi", "nihso" to "nihao")) {
            assertEquals(raw, PinyinTouchPromotionPolicy.Reason.ProtectedAbbreviation,
                policy.evaluate(proposal(raw, alternative, spatial = -2.0), "其他", listOf("原词")).reason)
            assertEquals(raw, PinyinTouchPromotionPolicy.Reason.Eligible,
                policy.evaluate(proposal(raw, alternative, spatial = -1.4), "其他", listOf("原词")).reason)
            // Such letters also need a clearly better spelling, not just any better one.
            assertEquals(raw, PinyinTouchPromotionPolicy.Reason.ProtectedAbbreviation,
                policy.evaluate(proposal(raw, alternative, spatial = -1.4, path = 3.9), "其他",
                    listOf("原词")).reason)
        }
        assertTrue(policy.evaluate(proposal(spatial = -1.4, path = 2.1), "古人", candidates).promoteToFirst)
        // No initials reading exists for these letters, so the general limit applies.
        assertTrue(policy.evaluate(proposal(spatial = -2.9), "古人", candidates).promoteToFirst)
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

    @Test fun `scores cannot substitute for missing or implausible spatial evidence`() {
        val original = proposal()
        assertEquals(PinyinTouchPromotionPolicy.Reason.MissingSpatialEvidence,
            policy.evaluate(original.copy(promotionEvidence = null, modelConfidence = 1f), "古人", candidates).reason)
        for (spatial in listOf(-3.01, -100.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(PinyinTouchPromotionPolicy.Reason.InsufficientSpatialAdvantage,
                policy.evaluate(proposal(spatial = spatial, path = 100.0), "古人", candidates).reason)
        }
        for (path in listOf(0.0, 1.99, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(PinyinTouchPromotionPolicy.Reason.InsufficientPathAdvantage,
                policy.evaluate(proposal(path = path), "古人", candidates).reason)
        }
        assertEquals(PinyinTouchPromotionPolicy.Reason.MissingSpatialEvidence,
            policy.evaluate(original.copy(promotionEvidence = original.promotionEvidence!!.copy(
                changedContacts = listOf(PinyinChangedContactSpatialEvidence(2, 'u', 'i', 5.0)))),
                "古人", candidates).reason)
    }

    @Test fun `many edits partial alternatives and non Chinese words are not first-slot evidence`() {
        assertTrue(policy.evaluate(proposal("gireb", "guren"), "古人", candidates).promoteToFirst)
        assertEquals(PinyinTouchPromotionPolicy.Reason.EditScope,
            policy.evaluate(proposal("gorwb", "guren"), "古人", candidates).reason)
        assertEquals(PinyinTouchPromotionPolicy.Reason.NonChineseCandidate,
            policy.evaluate(proposal(), "古人", listOf("jungle", "古人")).reason)
        assertEquals(PinyinTouchPromotionPolicy.Reason.NonChineseCandidate,
            policy.evaluate(proposal(), "jing", listOf("一人", "jing")).reason)
        assertEquals(PinyinTouchPromotionPolicy.Reason.NonChineseCandidate,
            policy.evaluate(proposal(), "古人", emptyList()).reason)
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

    @Test fun `real search attaches spatial evidence and promotes without personal offsets`() {
        val uniform = trackerProposal(emptyMap())!!
        assertEquals("guren", uniform.alternativeSpelling)
        assertTrue(uniform.promotionEvidence!!.changedContacts.single().logAlternativeOverOriginal < 0.0)
        assertTrue(policy.evaluate(uniform, "古人", candidates).promoteToFirst)
        val calibrated = trackerProposal(mapOf('u' to CenterOffset(.12f, 0f), 'i' to CenterOffset(.12f, 0f)))!!
        assertEquals("guren", calibrated.alternativeSpelling)
        assertTrue(policy.evaluate(calibrated, "古人", candidates).promoteToFirst)
        // A tap on the middle of the literal key never reaches the first slot.
        assertNull(trackerProposal(emptyMap(), center = true))
        val centered = trackerProposal(mapOf('u' to CenterOffset(.12f, 0f), 'i' to CenterOffset(.12f, 0f)), center = true)
        if (centered != null) assertFalse(policy.evaluate(centered, "古人", candidates).promoteToFirst)
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
