/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.WordLibrary
import org.fcitx.fcitx5.android.ui.main.WordLibraryPresentation as Presentation
import org.junit.Assert.*
import org.junit.Test

class WordLibraryPresentationTest {
    private fun word(text: String, reading: String, dictionary: String = "rime_ice", weight: String = "1") =
        WordLibrary.Word(dictionary, text, reading, weight)

    @Test fun sameTextGroupsWithoutLosingDictionaryReadingOrInternalWeight() {
        val records = listOf(
            word("重庆", "chong qing", "rime_ice", "c=9 d=0.2 t=800"),
            word("重庆", "zhong qing", "other", "-1"),
            word("重庆", "chong qing", "other", "5"),
            word("北京", "bei jing")
        )
        val groups = Presentation.groups(records, Presentation.Options())
        val group = groups.single { it.text == "重庆" }
        assertEquals("word:重庆", group.id)
        assertEquals(3, group.recordCount)
        assertEquals(records.take(3).toSet(), group.records.toSet())
        assertEquals(setOf("chongqing", "zhongqing"), group.readings.map(Presentation::normalizeReading).toSet())
        assertEquals(2, groups.size)
    }

    @Test fun textAndNormalizedPinyinSearchKeepAllRecordsOfTheMatchingWord() {
        val records = listOf(
            word("女儿", "nv er"),
            word("女儿", "nu er", "other"),
            word("绿色", "lv se"),
            word("重庆", "chong qing"),
            word("重庆", "zhong qing", "other")
        )
        for (query in listOf("女儿", " Nǚ Ér ", "NU: ER", "nü'er", "nv-er")) {
            val found = Presentation.groups(records, Presentation.Options(query = query))
            assertEquals("Pinyin variant: $query", listOf("女儿"), found.map { it.text })
            assertEquals("Search must not discard the other stored reading", 2, found.single().recordCount)
        }
        assertEquals(listOf("重庆"), Presentation.groups(records, Presentation.Options(query = "ZHONG'QING")).map { it.text })
        assertTrue(Presentation.groups(records, Presentation.Options(query = "does-not-exist")).isEmpty())
        assertEquals(3, Presentation.groups(records, Presentation.Options(query = "  ")).size)
    }

    @Test fun longAndMultipleReadingFiltersCombineAndCountUnicodeCharacters() {
        val records = listOf(
            word("𠀀你好", "yi ni hao"), // Three Unicode characters, four UTF-16 code units.
            word("𠀀你好啊", "yi ni hao a"),
            word("同一个读音", "TONG YI GE DU YIN"),
            word("同一个读音", "tong'yi-ge du yin", "other"),
            word("不同的读音", "bu tong de du yin"),
            word("不同的读音", "bu tong di du yin", "other"),
            word("重庆", "chong qing"),
            word("重庆", "zhong qing", "other")
        )
        val long = Presentation.groups(records, Presentation.Options(longOnly = true)).map { it.text }.toSet()
        assertEquals(setOf("𠀀你好啊", "同一个读音", "不同的读音"), long)
        val multiple = Presentation.groups(records, Presentation.Options(multipleReadingsOnly = true)).map { it.text }.toSet()
        assertEquals(setOf("不同的读音", "重庆"), multiple)
        assertEquals(listOf("不同的读音"), Presentation.groups(records,
            Presentation.Options(longOnly = true, multipleReadingsOnly = true)).map { it.text })
    }

    @Test fun sortingUsesReadingOrUnicodeLengthAndHasDeterministicTies() {
        val records = listOf(
            word("长长的术语", "zhuan you"),
            word("𠀀你好", "a a a"),
            word("您好", "nin hao"),
            word("你好", "ni hao"),
            word("你好", "li hao", "other"),
            word("一", "yi")
        )
        val pinyin = Presentation.groups(records, Presentation.Options()).map { it.text }
        assertEquals(listOf("𠀀你好", "你好", "您好", "一", "长长的术语"), pinyin)
        val byLength = Presentation.groups(records, Presentation.Options(sort = Presentation.Sort.LENGTH)).map { it.text }
        assertEquals(listOf("长长的术语", "𠀀你好", "你好", "您好", "一"), byLength)
        assertEquals(pinyin, Presentation.groups(records.reversed(), Presentation.Options()).map { it.text })
        assertEquals(byLength, Presentation.groups(records.reversed(), Presentation.Options(sort = Presentation.Sort.LENGTH)).map { it.text })
    }

    @Test fun ruleOnlyCandidatesRemainSearchableAndKeepTheirScopedIdentities() {
        val rules = listOf(
            WordLibrary.Rule("rime:rime_ice", "nv'er", "女儿", "hide"),
            WordLibrary.Rule("rime:other", "nv er", "女儿", "lower"),
            WordLibrary.Rule("rime:rime_ice", "predict:你好", "世界", "lower"),
            WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        )
        assertEquals(rules.toSet(), Presentation.rules(rules, Presentation.Options()).toSet())
        assertEquals(rules.take(2).toSet(), Presentation.rules(rules, Presentation.Options(query = "NǙ ER")).toSet())
        assertEquals(listOf(rules[2]), Presentation.rules(rules, Presentation.Options(query = "世界")))
        assertEquals(listOf(rules[3]), Presentation.rules(rules, Presentation.Options(query = "仮名")))
        assertTrue(Presentation.rules(rules, Presentation.Options(query = "missing")).isEmpty())
    }

    @Test fun selectionKeysSurviveWeightOrModeChangesButNeverConflateScopes() {
        val word = word("你好", "ni hao")
        assertEquals(Presentation.wordKey(word), Presentation.wordKey(word.copy(weight = "999")))
        assertNotEquals(Presentation.wordKey(word), Presentation.wordKey(word.copy(dictionary = "other")))
        assertNotEquals(Presentation.wordKey(word), Presentation.wordKey(word.copy(reading = "li hao")))
        val rule = WordLibrary.Rule("rime:rime_ice", "nihao", "你好", "hide")
        assertEquals(Presentation.ruleKey(rule), Presentation.ruleKey(rule.copy(mode = "lower")))
        assertNotEquals(Presentation.ruleKey(rule), Presentation.ruleKey(rule.copy(engine = "rime:other")))
        assertNotEquals(Presentation.ruleKey(rule), Presentation.ruleKey(rule.copy(reading = "predict:你")))
    }

    @Test fun allMatchingWordsStayReachableBeyondTheFormerTwoHundredRowLimit() {
        val words = (0 until 650).map { word("术语$it", "ci $it") }
        val groups = Presentation.groups(words, Presentation.Options(query = "术语"))
        assertEquals(650, groups.size)
        assertEquals(words.map { it.text }.toSet(), groups.map { it.text }.toSet())
    }
    @Test fun selectionSurvivesSearchingAndSelectAllAddsOnlyCurrentResults() {
        val selection = Presentation.Selection()
        selection.enter()
        selection.toggle(listOf("first-dictionary", "other-reading"))
        assertEquals(2, selection.count(listOf("first-dictionary", "other-reading")))
        assertEquals(2, selection.hiddenCount(listOf("another-result")))
        selection.selectAll(listOf("another-result", "offscreen-filtered-result"))
        assertEquals(setOf("first-dictionary", "other-reading", "another-result", "offscreen-filtered-result"), selection.keys)
        assertEquals(2, selection.hiddenCount(listOf("another-result", "offscreen-filtered-result")))
        assertFalse(selection.contains("filtered-out-unselected"))
        assertTrue(selection.active)
    }

    @Test fun partialGroupFailureRetainsOnlyTheOriginalFailedRecordForRetry() {
        val first = word("重庆", "chong qing")
        val failed = word("重庆", "zhong qing", "other")
        val keys = listOf(first, failed).map(Presentation::wordKey)
        val selection = Presentation.Selection()
        selection.toggle(keys)
        selection.retainFailures(listOf(Presentation.wordKey(failed)))
        assertTrue(selection.active)
        assertEquals(setOf(Presentation.wordKey(failed)), selection.keys)
        assertEquals(1, selection.count(keys))
        // Tapping a partially selected group selects all original records again.
        selection.toggle(keys)
        assertEquals(keys.toSet(), selection.keys)
        selection.toggle(keys)
        assertTrue(selection.keys.isEmpty())
        selection.retainFailures(emptyList())
        assertFalse(selection.active)
    }

    @Test fun dataRefreshPrunesVanishedIdentitiesAndExplicitExitClearsSelection() {
        val selection = Presentation.Selection()
        selection.selectAll(listOf("remaining", "removed"))
        selection.retainAvailable(listOf("remaining", "new-unselected"))
        assertEquals(setOf("remaining"), selection.keys)
        assertTrue(selection.active)
        selection.clear()
        assertFalse(selection.active)
        assertTrue(selection.keys.isEmpty())
    }

    @Test fun adjustedStatusFilterUsesSavedModesAndCombinesWithSearch() {
        val hidden = WordLibrary.Rule("anthy", "japanese", "仮名", "hide")
        val lowered = WordLibrary.Rule("rime:rime_ice", "nihao", "你好", "lower")
        val sameTextHidden = lowered.copy(engine = "rime:other", mode = "hide")
        val rules = listOf(hidden, lowered, sameTextHidden)
        assertEquals(listOf(hidden, sameTextHidden), Presentation.rules(rules,
            Presentation.Options(ruleStatus = Presentation.RuleStatus.FORGOTTEN)))
        assertEquals(listOf(lowered), Presentation.rules(rules,
            Presentation.Options(query = "NI HAO", ruleStatus = Presentation.RuleStatus.LOWERED)))
        assertTrue(Presentation.rules(rules,
            Presentation.Options(query = "仮名", ruleStatus = Presentation.RuleStatus.LOWERED)).isEmpty())
        assertEquals(3, Presentation.rules(rules, Presentation.Options(ruleStatus = Presentation.RuleStatus.ALL)).size)
    }

    @Test fun normalizationKeepsDistinctJapaneseReadingsWhileIgnoringLatinPinyinTones() {
        assertNotEquals(Presentation.normalizeReading("が"), Presentation.normalizeReading("か"))
        assertNotEquals(Presentation.normalizeReading("ば"), Presentation.normalizeReading("は"))
        assertEquals(Presentation.normalizeReading("が"), Presentation.normalizeReading("か\u3099"))
        assertEquals("nver", Presentation.normalizeReading("nǚ ér"))
        val readings = listOf(WordLibrary.Rule("anthy", "が", "候補", "hide"),
            WordLibrary.Rule("anthy", "か", "候補", "lower"))
        assertEquals(2, Presentation.rules(readings, Presentation.Options(multipleReadingsOnly = true)).size)
        assertEquals(listOf(readings[0]), Presentation.rules(readings, Presentation.Options(query = "が")))
    }

}
