package app.zhijuan.core.model

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BookLengthPolicyTest {
    @Test
    fun minimumsAreEightyThreeHundredAndThreeHundredOne() {
        assertEquals(80, BookLengthPolicy.minimumChapterCount(BookLengthMode.SHORT))
        assertEquals(300, BookLengthPolicy.minimumChapterCount(BookLengthMode.MEDIUM))
        assertEquals(301, BookLengthPolicy.minimumChapterCount(BookLengthMode.LONG))
    }

    @Test
    fun defaultTargetsKeepShortAndMediumAtTheirMinimums() {
        assertEquals(80, BookLengthPolicy.targetChapterCount(BookLengthMode.SHORT, null))
        assertEquals(300, BookLengthPolicy.targetChapterCount(BookLengthMode.MEDIUM, null))
        assertNull(BookLengthPolicy.targetChapterCount(BookLengthMode.LONG, null))
        assertEquals(888, BookLengthPolicy.targetChapterCount(BookLengthMode.LONG, 888))
        assertNull(BookLengthPolicy.targetChapterCount(BookLengthMode.LONG, 300))
    }

    @Test
    fun everyModeAcceptsTargetsAtOrAboveItsMinimum() {
        assertDoesNotThrow {
            BookLengthPolicy.requireValidSelection(BookLengthMode.SHORT, 80, 120, 1)
            BookLengthPolicy.requireValidSelection(BookLengthMode.MEDIUM, 300, 600, 1)
            BookLengthPolicy.requireValidSelection(BookLengthMode.LONG, 301, 1_000, 1)
        }
    }

    @Test
    fun invalidMinimumTargetOrSchemaFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            BookLengthPolicy.requireValidSelection(BookLengthMode.SHORT, 1, 80, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookLengthPolicy.requireValidSelection(BookLengthMode.MEDIUM, 300, 299, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookLengthPolicy.requireValidSelection(BookLengthMode.LONG, 301, 500, 99)
        }
    }
}
