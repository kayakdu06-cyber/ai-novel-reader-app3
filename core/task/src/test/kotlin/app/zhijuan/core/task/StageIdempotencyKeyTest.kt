package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StageIdempotencyKeyTest {
    @Test
    fun `same logical stage has a stable key`() {
        val first = key("job-1", "chapter-7", "input-v3")
        val second = key("job-1", "chapter-7", "input-v3")
        assertEquals(first, second)
        assertEquals(64, first.value.length)
    }

    @Test
    fun `input version change creates a new key`() {
        assertNotEquals(
            key("job-1", "chapter-7", "input-v3"),
            key("job-1", "chapter-7", "input-v4"),
        )
    }

    @Test
    fun `length prefix avoids ambiguous concatenation`() {
        assertNotEquals(
            key("ab", "c", "d"),
            key("a", "bc", "d"),
        )
    }

    @Test
    fun `blank components are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            key(" ", "chapter-7", "input-v3")
        }
    }

    private fun key(jobId: String, targetId: String, inputHash: String) =
        StageIdempotencyKey.create(
            jobId = jobId,
            phase = GenerationPhase.DRAFT_CHAPTER,
            targetId = targetId,
            inputVersionHash = inputHash,
        )
}

