package app.zhijuan.core.database.search

import java.text.Normalizer
import java.util.Locale

/**
 * Produces ASCII tokens for deterministic CJK recall in SQLite FTS. All derived tokens remain
 * inside the same encrypted database as the source text.
 */
object SearchIndexText {
    fun indexTerms(text: String): String = tokenize(text, forQuery = false)
        .distinct()
        .joinToString(" ")

    fun matchExpression(query: String): String? = tokenize(query, forQuery = true)
        .distinct()
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(" AND ")

    private fun tokenize(
        input: String,
        forQuery: Boolean,
    ): List<String> {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val tokens = linkedSetOf<String>()
        val hanRun = mutableListOf<Int>()
        val wordRun = StringBuilder()

        fun flushHan() {
            if (hanRun.isEmpty()) return
            if (!forQuery || hanRun.size == 1) {
                hanRun.forEach { point -> tokens += "c${point.toString(16)}" }
            }
            hanRun.windowed(size = 2, step = 1).forEach { pair ->
                tokens += "g${pair[0].toString(16)}_${pair[1].toString(16)}"
            }
            hanRun.clear()
        }

        fun flushWord() {
            if (wordRun.isEmpty()) return
            tokens += "w$wordRun"
            wordRun.clear()
        }

        normalized.codePoints().forEachOrdered { point ->
            when {
                Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN -> {
                    flushWord()
                    hanRun += point
                }

                Character.isLetterOrDigit(point) -> {
                    flushHan()
                    wordRun.appendCodePoint(point)
                }

                else -> {
                    flushHan()
                    flushWord()
                }
            }
        }
        flushHan()
        flushWord()
        return tokens.toList()
    }
}
