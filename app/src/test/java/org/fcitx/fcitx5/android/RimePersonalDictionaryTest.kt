/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.rime.RimePersonalDictionary
import org.junit.Assert.*
import org.junit.Test

class RimePersonalDictionaryTest {
    @Test fun utf8EntriesNormalizeCodesAndPreservePersonalWords() {
        val entries = RimePersonalDictionary.parse("\uFEFF# 我的词库\n双卡双待\tSHUANG  KA SHUANG DAI\n女儿\tnü er\t200\n")
        assertEquals("shuang ka shuang dai", entries[0].code)
        assertEquals("nv er", entries[1].code)
        assertEquals(100, entries[0].weight)
        assertEquals(entries, RimePersonalDictionary.readStored(RimePersonalDictionary.render(entries)))
    }
    @Test fun repeatedImportsMergeAndReplaceOnlyMatchingWordAndCode() {
        val first = RimePersonalDictionary.parse("霓虹键盘\tni hong jian pan\t100\n行\txing\t30")
        val second = RimePersonalDictionary.parse("霓虹键盘\tni hong jian pan\t200\n行\thang\t20")
        val merged = RimePersonalDictionary.merge(first, second)
        assertEquals(3, merged.size)
        assertEquals(200, merged.first { it.text == "霓虹键盘" }.weight)
        assertEquals(merged, RimePersonalDictionary.merge(merged, second))
    }
    @Test fun malformedLineReportsItsOriginalLineAndCannotInjectYaml() {
        try {
            RimePersonalDictionary.parse("# test\n双卡双待\tshuang ka shuang dai\npatch: malicious\n")
            fail("Malformed source must not be saved")
        } catch (error: RimePersonalDictionary.InvalidLine) { assertEquals(3, error.lineNumber) }
    }
    @Test fun emptyOverweightAndUnknownStoredFormatsAreRejected() {
        for (source in listOf("#empty", "词\tci\t-1", "词\tci\t100001", "词\tci 2")) {
            assertThrows(IllegalArgumentException::class.java) { RimePersonalDictionary.parse(source) }
        }
        assertThrows(IllegalArgumentException::class.java) { RimePersonalDictionary.readStored("existing data") }
    }
}
