package app.zhijuan.core.database.generation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterPlanV2FrozenSourcesTest {
    @Test
    fun `freeze canonicalizes manifests and binds authority hashes`() {
        val first = freeze(
            expectation = """{"contextEvidenceHash":"$HASH_C","policyCompilationHash":"$HASH_B","activationHash":"$HASH_A","chapterId":"chapter-2"}""",
            activation = """{"capabilities":["core-narrative","character-continuity"],"activationHash":"$HASH_A"}""",
            policy = """{"selected":["policy.core-narrative.v1"],"policyCompilationHash":"$HASH_B"}""",
        )
        val second = freeze(
            expectation = """{"chapterId":"chapter-2","activationHash":"$HASH_A","policyCompilationHash":"$HASH_B","contextEvidenceHash":"$HASH_C"}""",
            activation = """{"activationHash":"$HASH_A","capabilities":["core-narrative","character-continuity"]}""",
            policy = """{"policyCompilationHash":"$HASH_B","selected":["policy.core-narrative.v1"]}""",
        )
        assertEquals(first.expectationHash, second.expectationHash)
        assertEquals(first.activationManifestHash, second.activationManifestHash)
        assertEquals(first.policyManifestHash, second.policyManifestHash)
    }

    @Test
    fun `freeze rejects a manifest that disagrees with authority hash`() {
        assertThrows(IllegalArgumentException::class.java) {
            freeze(
                expectation = """{"activationHash":"$HASH_A","policyCompilationHash":"$HASH_B","contextEvidenceHash":"$HASH_C"}""",
                activation = """{"activationHash":"$HASH_D"}""",
                policy = """{"policyCompilationHash":"$HASH_B"}""",
            )
        }
    }

    private fun freeze(expectation: String, activation: String, policy: String) =
        ChapterPlanV2FrozenSources.freeze(
            expectationJson = expectation,
            activationManifestJson = activation,
            activationHash = HASH_A,
            policyManifestJson = policy,
            policyCompilationHash = HASH_B,
            contextEvidenceHash = HASH_C,
        )

    private companion object {
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
        val HASH_C = "c".repeat(64)
        val HASH_D = "d".repeat(64)
    }
}
