package app.zhijuan.feature.generation

import app.zhijuan.provider.common.ProviderFinishReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterDraftV1StreamPayloadDecoderTest {
    @Test
    fun `complete envelope emits only decoded body across arbitrary fragments`() {
        val decoder = ChapterDraftV1StreamPayloadDecoder()
        val emitted = listOf(" {\"bo", "dy\" : \"中文\\n", "动作\\uD83D\\uDE42\"", " } ")
            .joinToString("") { decoder.onStructuredDelta(it) }

        assertEquals("中文\n动作🙂", emitted)
        assertEquals(ProviderPayloadCompletion.COMPLETE, decoder.complete(ProviderFinishReason.STOP))
    }

    @Test
    fun `length accepts only a body prefix made of complete code points`() {
        val decoder = ChapterDraftV1StreamPayloadDecoder()
        val emitted = decoder.onStructuredDelta("{\"body\":\"仍在继续的正文\\u4E2")

        assertEquals("仍在继续的正文", emitted)
        assertEquals(
            ProviderPayloadCompletion.TRUNCATED_SAFE_PREFIX,
            decoder.complete(ProviderFinishReason.LENGTH),
        )
    }

    @Test
    fun `continuation anchor is verified across fragments and never written twice`() {
        val anchor = "上一个片段末尾的精确锚点。"
        val decoder = ChapterDraftV1StreamPayloadDecoder(anchor)
        val first = decoder.onStructuredDelta("{\"body\":\"上一个片段末")
        val second = decoder.onStructuredDelta("尾的精确锚点。新的正文继续。\"}")

        assertEquals("", first)
        assertEquals("新的正文继续。", second)
        assertEquals(ProviderPayloadCompletion.COMPLETE, decoder.complete(ProviderFinishReason.STOP))
    }

    @Test
    fun `wrong or incomplete anchor fails closed`() {
        val wrong = ChapterDraftV1StreamPayloadDecoder("正确锚点")
        assertEquals("", wrong.onStructuredDelta("{\"body\":\"错误锚点和正文\"}"))
        assertEquals(ProviderPayloadCompletion.INVALID, wrong.complete(ProviderFinishReason.STOP))

        val missing = ChapterDraftV1StreamPayloadDecoder("完整锚点")
        assertEquals("", missing.onStructuredDelta("{\"body\":\"完整\"}"))
        assertEquals(ProviderPayloadCompletion.INVALID, missing.complete(ProviderFinishReason.STOP))
    }

    @Test
    fun `extra fields text deltas and invalid json are rejected`() {
        val extra = ChapterDraftV1StreamPayloadDecoder()
        extra.onStructuredDelta("{\"body\":\"正文\",\"extra\":1}")
        assertEquals(ProviderPayloadCompletion.INVALID, extra.complete(ProviderFinishReason.STOP))

        val text = ChapterDraftV1StreamPayloadDecoder()
        text.onTextDelta("正文")
        assertEquals(ProviderPayloadCompletion.INVALID, text.complete(ProviderFinishReason.STOP))
    }

    @Test
    fun `continuation enforces chapter limit against saved and new bytes together`() {
        val decoder = ChapterDraftV1StreamPayloadDecoder(
            expectedContinuationAnchor = "精确锚点",
            initialUtf8Bytes = app.zhijuan.core.task.ChapterDraftContinuationPolicyV1
                .MAXIMUM_CHAPTER_UTF8_BYTES - 3,
        )

        assertEquals("界", decoder.onStructuredDelta("{\"body\":\"精确锚点界"))
        assertEquals("", decoder.onStructuredDelta("外"))
        assertEquals(ProviderPayloadCompletion.INVALID, decoder.complete(ProviderFinishReason.LENGTH))
    }
}
