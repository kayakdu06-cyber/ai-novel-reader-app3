package app.zhijuan.provider.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderCapabilityProbeTest {
    @Test
    fun `inconclusive checks remain unknown instead of becoming unsupported`() {
        val snapshot = ProviderCapabilityProbeEvidence(
            streaming = CapabilityProbeOutcome.SUPPORTED,
            observedStreamFormat = ProviderStreamFormat.SSE,
            usageInStream = CapabilityProbeOutcome.SUPPORTED,
        ).toSnapshot(
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            modelId = ProviderModelId.from("model-a"),
            adapterVersion = "adapter-1",
            verifiedAt = 1_000,
            validForMillis = ProviderCapabilityProbeEvidence.DEFAULT_TTL_MILLIS,
        )

        assertEquals(CapabilitySupport.SUPPORTED, snapshot.streaming)
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.usageInStream)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.structuredOutput)
        assertEquals(
            1_000 + ProviderCapabilityProbeEvidence.DEFAULT_TTL_MILLIS,
            snapshot.expiresAt,
        )
    }

    @Test
    fun `explicit field rejection may record unsupported`() {
        val snapshot = ProviderCapabilityProbeEvidence(
            streaming = CapabilityProbeOutcome.EXPLICITLY_UNSUPPORTED,
            observedStreamFormat = ProviderStreamFormat.NONE,
            seed = CapabilityProbeOutcome.EXPLICITLY_UNSUPPORTED,
        ).toSnapshot(
            protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
            modelId = ProviderModelId.from("model-a"),
            adapterVersion = "adapter-1",
            verifiedAt = 1_000,
            validForMillis = ProviderCapabilityProbeEvidence.MINIMUM_TTL_MILLIS,
        )

        assertEquals(CapabilitySupport.UNSUPPORTED, snapshot.streaming)
        assertEquals(CapabilitySupport.UNSUPPORTED, snapshot.seed)
    }

    @Test
    fun `contradictory stream evidence and unbounded freshness are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCapabilityProbeEvidence(
                streaming = CapabilityProbeOutcome.SUPPORTED,
                observedStreamFormat = ProviderStreamFormat.NONE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCapabilityProbeEvidence(
                seed = CapabilityProbeOutcome.SUPPORTED,
            ).toSnapshot(
                protocol = ProviderProtocol.OPENAI_CHAT_COMPAT,
                modelId = ProviderModelId.from("model-a"),
                adapterVersion = "adapter-1",
                verifiedAt = 1_000,
                validForMillis = ProviderCapabilityProbeEvidence.MAXIMUM_TTL_MILLIS + 1,
            )
        }
    }
}
