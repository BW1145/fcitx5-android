/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import org.fcitx.fcitx5.android.data.WordLibrary
import java.text.Normalizer
import java.util.Locale

/** Pure presentation rules. Every grouped row keeps the original engine records. */
object WordLibraryPresentation {
    enum class Sort { PINYIN, LENGTH }
    enum class RuleStatus { ALL, FORGOTTEN, LOWERED }

    data class Options(
        val query: String = "",
        val sort: Sort = Sort.PINYIN,
        val longOnly: Boolean = false,
        val multipleReadingsOnly: Boolean = false,
        val ruleStatus: RuleStatus = RuleStatus.ALL
    )

    data class WordGroup(val text: String, val records: List<WordLibrary.Word>) {
        val id: String get() = "word:$text"
        val readings: List<String> = records.map { it.reading }
            .distinctBy(::normalizeReading).sortedBy(::normalizeReading)
        val recordCount: Int get() = records.size
    }

    /** Selection survives searching; its scope is explicit and independent of the viewport. */
    class Selection {
        private val selected = linkedSetOf<String>()
        val keys: Set<String> get() = selected
        var active: Boolean = false
            private set

        fun enter() { active = true }
        fun contains(key: String) = key in selected
        fun count(keys: Collection<String>) = keys.count { it in selected }
        fun hiddenCount(visibleKeys: Collection<String>): Int = (selected - visibleKeys.toSet()).size
        fun toggle(keys: Collection<String>) {
            active = true
            if (keys.all { it in selected }) selected.removeAll(keys.toSet()) else selected.addAll(keys)
        }
        fun selectAll(keys: Collection<String>) { active = true; selected.addAll(keys) }
        fun retainFailures(keys: Collection<String>) {
            selected.clear(); selected.addAll(keys); active = selected.isNotEmpty()
        }
        fun retainAvailable(keys: Collection<String>) { selected.retainAll(keys.toSet()) }
        fun clear() { selected.clear(); active = false }
    }

    private val numericTone = Regex("(?<=[a-z])[1-5]")

    /** Search keys only: never write these normalized values back to the engine. */
    fun normalizeReading(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT).replace("u:", "v")
        val result = StringBuilder()
        for (char in decomposed) {
            when {
                char == '\u0308' && result.lastOrNull() == 'u' -> result.setCharAt(result.lastIndex, 'v')
                Character.getType(char) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(char) == Character.COMBINING_SPACING_MARK.toInt() -> {
                    // Strip Latin pinyin tone marks, but preserve e.g. Japanese dakuten.
                    if (result.lastOrNull()?.let { it in 'a'..'z' } != true) result.append(char)
                }
                char.isLetterOrDigit() -> result.append(char)
            }
        }
        // Numeric pinyin tone notation is equivalent to its unnumbered form.
        return Normalizer.normalize(result.toString(), Normalizer.Form.NFC).replace(numericTone, "")
    }

    private fun textKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    private fun length(value: String) = value.codePointCount(0, value.length)
    private fun key(vararg fields: String) = fields.joinToString("") { "${it.length}:$it" }

    fun wordKey(word: WordLibrary.Word): String = key(word.dictionary, word.text, word.reading)
    fun ruleKey(rule: WordLibrary.Rule): String = key(rule.engine, rule.reading, rule.text)

    fun groups(words: List<WordLibrary.Word>, options: Options = Options()): List<WordGroup> {
        val query = textKey(options.query)
        val readingQuery = normalizeReading(options.query)
        val comparator = compareBy<WordGroup> { normalizeReading(it.readings.firstOrNull().orEmpty()) }
            .thenBy { it.text }
        return words.groupBy { it.text }.map { (text, records) ->
            WordGroup(text, records.sortedWith(compareBy<WordLibrary.Word> { normalizeReading(it.reading) }
                .thenBy { it.reading }.thenBy { it.dictionary }))
        }.filter { group ->
            (!options.longOnly || length(group.text) >= 4) &&
                (!options.multipleReadingsOnly || group.readings.size > 1) &&
                (query.isEmpty() || textKey(group.text).contains(query) ||
                    readingQuery.isNotEmpty() && group.readings.any { normalizeReading(it).contains(readingQuery) })
        }.sortedWith(if (options.sort == Sort.LENGTH) compareByDescending<WordGroup> { length(it.text) }.then(comparator) else comparator)
    }

    /** Rules are a separate data source: an absent learning record must not hide one. */
    fun rules(rules: List<WordLibrary.Rule>, options: Options = Options()): List<WordLibrary.Rule> {
        val query = textKey(options.query)
        val readingQuery = normalizeReading(options.query)
        val readingCounts = rules.groupBy { it.text }.mapValues { (_, items) -> items.map { normalizeReading(it.reading) }.distinct().size }
        val comparator = compareBy<WordLibrary.Rule> { normalizeReading(it.reading) }
            .thenBy { it.text }.thenBy { it.engine }.thenBy { it.reading }
        return rules.filter {
            (options.ruleStatus == RuleStatus.ALL || it.mode == if (options.ruleStatus == RuleStatus.FORGOTTEN) "hide" else "lower") &&
            (!options.longOnly || length(it.text) >= 4) &&
                (!options.multipleReadingsOnly || readingCounts.getValue(it.text) > 1) &&
                (query.isEmpty() || textKey(it.text).contains(query) ||
                    readingQuery.isNotEmpty() && normalizeReading(it.reading).contains(readingQuery))
        }.sortedWith(if (options.sort == Sort.LENGTH) compareByDescending<WordLibrary.Rule> { length(it.text) }.then(comparator) else comparator)
    }
}
