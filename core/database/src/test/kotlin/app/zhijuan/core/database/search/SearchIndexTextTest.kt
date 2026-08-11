package app.zhijuan.core.database.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SearchIndexTextTest {
    @Test
    fun `Chinese query becomes overlapping deterministic bigrams`() {
        assertEquals(
            "c7384 c94c1 c5251 g7384x94c1 g94c1x5251",
            SearchIndexText.indexTerms("玄铁剑"),
        )
    }

    @Test
    fun `query uses portable implicit AND so all overlapping bigrams are required`() {
        assertEquals(
            "g7384x94c1 g94c1x5251",
            SearchIndexText.matchExpression("玄铁剑"),
        )
    }

    @Test
    fun `full width and case variants normalize to same Latin token`() {
        assertEquals(SearchIndexText.indexTerms("ＡＰＩ"), SearchIndexText.indexTerms("api"))
    }

    @Test
    fun `punctuation cannot inject FTS syntax`() {
        val expression = SearchIndexText.matchExpression("玄铁 OR secret*")

        assertNotNull(expression)
        assertFalse(expression!!.contains("secret*"))
        assertFalse(expression.contains(" OR "))
    }

    @Test
    fun `blank query has no match expression`() {
        assertNull(SearchIndexText.matchExpression("  ，。\n"))
    }
}
