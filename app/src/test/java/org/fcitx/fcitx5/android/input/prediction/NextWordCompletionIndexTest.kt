/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NextWordCompletionIndexTest {
    private fun fixture(vararg rows: String): ByteArray =
        ("# AXiang next-word completions v1\n# entries: ${rows.size}\n" +
            "# source: public synthetic parser fixture\n" + rows.joinToString("\n", postfix = "\n"))
            .toByteArray(Charsets.UTF_8)

    private fun fixtureV2(vararg rows: String): ByteArray =
        ("# AXiang next-word completions v2\n# entries: ${rows.size}\n" +
            "# source: public synthetic parser fixture; lexical specificity is not probability\n" +
            rows.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8)

    private fun invalid(bytes: ByteArray) {
        try {
            NextWordCompletionIndex.parse(bytes)
            fail("Malformed or incomplete index must not be used")
        } catch (_: Exception) { }
    }

    @Test fun binaryLookupPreservesSourceOrderAndEmptyGroups() {
        val index = NextWordCompletionIndex.parse(fixture("做\t好,到,饭\t一个,什么,作业", "谢谢\t你,您\t"))
        assertEquals(listOf("好", "到", "饭"), index.lookup("做")!!.singles)
        assertEquals(listOf("一个", "什么", "作业"), index.lookup("做")!!.multis)
        assertEquals(emptyList<String>(), index.lookup("谢谢")!!.multis)
        assertNull(index.lookup("未知"))
        assertNull(index.lookup(""))
        assertNull(index.lookup("abc"))
        assertFalse(index.lookup("做")!!.specificContinuation)
    }

    @Test fun longestMatchingContextWinsAndPunctuationEndsTheEarlierPrefix() {
        val index = NextWordCompletionIndex.parse(fixture("做\t好\t什么", "想做\t好\t作业"))
        assertEquals(listOf("想做", "做"), index.trailingMatches("我想做").map { it.prefix })
        assertEquals(listOf("做"), index.trailingMatches("想，做").map { it.prefix })
        assertTrue(index.trailingMatches("做！").isEmpty())
        assertTrue(index.trailingMatches("做a").isEmpty())
        assertTrue(index.trailingMatches("").isEmpty())
    }

    @Test fun validatedBufferCannotBeMutatedByTheLoader() {
        val bytes = fixture("做\t饭\t什么")
        val index = NextWordCompletionIndex.parse(bytes)
        bytes.fill(0)
        assertEquals(listOf("什么"), index.lookup("做")!!.multis)
    }

    @Test fun unsignedUtf8OrderAndSupplementaryHanDoNotSplitSurrogates() {
        val compatibility = "\uF900"
        val supplementary = String(Character.toChars(0x20000))
        val index = NextWordCompletionIndex.parse(fixture("$compatibility\t好\t什么", "$supplementary\t饭\t作业"))
        assertEquals(listOf("饭"), index.lookup(supplementary)!!.singles)
        assertEquals(listOf(supplementary), index.trailingMatches("天$supplementary").map { it.prefix })
        invalid(fixture("$supplementary\t饭\t作业", "$compatibility\t好\t什么"))
    }

    @Test fun sourceCandidatePoolsLargerThanTheOld512ByteRowRemainBoundedAndReadable() {
        val singles = (0 until 24).map { (0x4e00 + it).toChar().toString() }
        val multis = (0 until 64).map { "天${(0x4e00 + it).toChar()}地人" }
        val row = "做\t${singles.joinToString(",")}\t${multis.joinToString(",")}"
        assertTrue(row.toByteArray().size > 512)
        val index = NextWordCompletionIndex.parse(fixture(row))
        assertEquals(24, index.lookup("做")!!.singles.size)
        assertEquals(64, index.lookup("做")!!.multis.size)
    }

    @Test fun corruptedOrUnsupportedVersionCannotBecomeAReadyIndex() {
        invalid(byteArrayOf())
        invalid(fixture("做\t饭\t什么").copyOfRange(1, fixture("做\t饭\t什么").size))
        invalid(fixture("做\t饭\t什么").toString(Charsets.UTF_8).replace(" v1", " v2").toByteArray())
        invalid(ByteArray(NextWordCompletionIndex.MAX_BYTES + 1) { 10 })
    }

    @Test fun aTruncatedCompleteLookingRowIsDetectedByTheDeclaredCount() {
        val original = fixture("做\t飯\t什么", "谢谢\t你\t大家").toString(Charsets.UTF_8)
        invalid(original.substringBeforeLast("谢谢").toByteArray())
        invalid(fixture("做\t飯\t什么").dropLast(1).toByteArray())
        invalid(original.replace("# entries: 2", "# entries: 1").toByteArray())
        invalid(original.replace("# entries: 2", "# entries: abc").toByteArray())
    }

    @Test fun invalidUtf8AndNonHanCandidatesFailTheWholeOptionalAsset() {
        val before = "# AXiang next-word completions v1\n# entries: 1\n做\t饭\t".toByteArray()
        invalid(before + byteArrayOf(0xc0.toByte(), 0xaf.toByte(), 10))
        for (row in listOf("做\t饭\twhat", "做\t饭\t什么！", "a\t饭\t什么", "做\t\t")) invalid(fixture(row))
    }

    @Test fun duplicateUnsortedAndAmbiguousColumnsAreRejected() {
        for (rows in listOf(arrayOf("做\t饭\t什么", "做\t好\t作业"),
            arrayOf("谢谢\t你\t大家", "做\t饭\t什么"), arrayOf("做\t饭,饭\t什么"),
            arrayOf("做\t饭\t什么,什么"), arrayOf("做\t饭\t什么\textra"),
            arrayOf("做\t饭\t什么", "# late header"))) invalid(fixture(*rows))
    }

    @Test fun perRowAndPerGroupBudgetsApplyBeforeAnyCandidateIsReturned() {
        val singles = (0 until 25).joinToString(",") { (0x4e00 + it).toChar().toString() }
        val multis = (0 until 65).joinToString(",") { "天${(0x4e00 + it).toChar()}" }
        invalid(fixture("做\t$singles\t什么"))
        invalid(fixture("做\t饭\t$multis"))
        invalid(fixture("做\t饭\t" + "天".repeat(400)))
        invalid(fixture("做什么工作\t饭\t今天"))
        invalid(fixture("做\t饭\t今天工作了"))
    }

    @Test fun provenanceHeaderHasItsOwnBoundWithoutAllowingUnboundedRows() {
        val bytes = fixture("做\t饭\t什么").toString(Charsets.UTF_8)
            .replace("# source: public synthetic parser fixture", "# " + "a".repeat(1800))
        assertNotNull(NextWordCompletionIndex.parse(bytes.toByteArray()).lookup("做"))
        invalid(bytes.replace("a".repeat(1800), "a".repeat(9000)).toByteArray())
    }

    @Test fun versionTwoRetainsSixCharacterPrefixesAndSuffixesAndItsLexicalFlag() {
        val index = NextWordCompletionIndex.parse(fixtureV2("古诗词的前缀\t字\t接着写下半句,下一句\t1", "通用前缀\t字\t继续\t0"))
        val specific = index.lookup("古诗词的前缀")!!
        assertTrue(specific.specificContinuation)
        assertEquals(listOf("接着写下半句", "下一句"), specific.multis)
        assertFalse(index.lookup("通用前缀")!!.specificContinuation)
        assertEquals("古诗词的前缀", index.trailingMatches("这是古诗词的前缀").first().prefix)
    }

    @Test fun versionTwoAcceptsTheBoundedThreeSourcePoolsWithoutExpandingARuntimeDictionary() {
        val singles = (0 until 34).map { (0x4e00 + it).toChar().toString() }
        val multis = (0 until 74).map { "天${(0x4e00 + it).toChar()}地人日月" }
        val row = "前缀\t${singles.joinToString(",")}\t${multis.joinToString(",")}\t0"
        val index = NextWordCompletionIndex.parse(fixtureV2(row))
        assertEquals(34, index.lookup("前缀")!!.singles.size)
        assertEquals(74, index.lookup("前缀")!!.multis.size)
        assertTrue(row.toByteArray().size > 1024)
    }

    @Test fun versionTwoRejectsInvalidFlagShapeAndExceedingCorpusBudgets() {
        for (row in listOf("前缀\t字\t继续\t2", "前缀\t字\t继续\ttrue", "字\t好\t继续\t1",
            "前缀\t字\t\t1", "前缀\t字\t继续", "前缀\t字\t继续\t0\textra",
            "超过六个字前缀\t字\t继续\t0", "前缀\t字\t超过六个字词尾\t0")) invalid(fixtureV2(row))
        val singles = (0 until 35).joinToString(",") { (0x4e00 + it).toChar().toString() }
        val multis = (0 until 75).joinToString(",") { "天${(0x4e00 + it).toChar()}" }
        invalid(fixtureV2("前缀\t$singles\t继续\t0"))
        invalid(fixtureV2("前缀\t字\t$multis\t0"))
    }

    @Test fun actualShippedReadOnlyAssetFitsTheSameStrictParserAndRetainsUsefulSourceWords() {
        val relative = "src/main/assets/typing/next_word_completions.tsv"
        val file = File(relative).takeIf(File::isFile) ?: File("app/$relative")
        val index = NextWordCompletionIndex.parse(file.readBytes())
        val continuation = index.lookup("做")!!
        assertTrue(continuation.singles.contains("饭"))
        assertTrue(continuation.multis.contains("什么"))
        assertTrue(continuation.multis.contains("作业"))
        assertTrue(continuation.multis.contains("准备"))
        assertTrue(index.lookup("谢谢")!!.singles.contains("你"))
        assertTrue(index.lookup("今天")!!.multis.contains("晚上"))
        val lyric = index.lookup("天青色")!!
        assertTrue(lyric.multis.contains("等烟雨"))
        assertTrue(lyric.specificContinuation)
        val idiom = index.lookup("一帆")!!
        assertTrue(idiom.multis.contains("风顺"))
        assertTrue(idiom.specificContinuation)
        for (prefix in listOf("我", "你", "在")) assertFalse(index.lookup(prefix)!!.specificContinuation)
    }
}
