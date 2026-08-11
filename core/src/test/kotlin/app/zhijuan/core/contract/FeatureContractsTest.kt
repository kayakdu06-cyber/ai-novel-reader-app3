package app.zhijuan.core.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FeatureContractsTest {
    @Test
    fun `connection selection rejects blank identities`() {
        assertThrows<IllegalArgumentException> {
            CurrentConnectionSelection("", "model")
        }
        assertThrows<IllegalArgumentException> {
            CurrentConnectionSelection("connection", " ")
        }
    }

    @Test
    fun `library summaries enforce stable identity and ordinals`() {
        assertThrows<IllegalArgumentException> {
            LibraryBookSummary("book", "title", -1)
        }
        assertThrows<IllegalArgumentException> {
            LibraryChapterSummary("chapter", 0, "title")
        }
        assertEquals(3, LibraryBookSummary("book", "title", 3).completedChapterCount)
    }
}
