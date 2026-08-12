package app.zhijuan.reader.creation

import app.zhijuan.core.database.library.StoredBookCreationSummary
import app.zhijuan.core.model.BookLengthMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookCreationConfirmationMappingTest {
    @Test
    fun mapsOnlyTheModelReferenceFromTheFrozenSnapshot() {
        val confirmation = summary(
            modelPreferenceJson =
                "{\"connectionId\":\"connection-1\",\"modelId\":\"deepseek-chat\",\"source\":\"USER\"}",
        ).toBookCreationConfirmation()

        assertEquals("book-1", confirmation?.bookId)
        assertEquals("snapshot-1", confirmation?.snapshotId)
        assertEquals("雨夜重逢", confirmation?.title)
        assertEquals(300, confirmation?.minimumChapterCount)
        assertEquals(300, confirmation?.targetChapterCount)
        assertEquals("deepseek-chat", confirmation?.modelId)
        assertEquals("connection-1", confirmation?.connectionId)
        assertEquals("a".repeat(64), confirmation?.contentHash)
    }

    @Test
    fun malformedOrMissingModelReferenceFailsClosed() {
        assertNull(summary("not-json").toBookCreationConfirmation())
        assertNull(summary("{\"connectionId\":\"connection-1\"}").toBookCreationConfirmation())
        assertNull(summary("{\"modelId\":\"   \"}").toBookCreationConfirmation())
    }

    private fun summary(modelPreferenceJson: String) = StoredBookCreationSummary(
        bookId = "book-1",
        snapshotId = "snapshot-1",
        title = "雨夜重逢",
        lengthMode = BookLengthMode.MEDIUM,
        minimumChapterCount = 300,
        targetChapterCount = 300,
        lengthPolicySchemaVersion = 1,
        modelPreferenceJson = modelPreferenceJson,
        contentHash = "a".repeat(64),
    )
}
