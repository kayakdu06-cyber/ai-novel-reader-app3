package app.zhijuan.core.contract

data class GenerationBudgetConfirmation(
    val requestTokenHardLimit: Long,
    val bookTokenHardLimit: Long,
    val dailyTokenHardLimit: Long,
    val dailyZoneId: String,
    val priceUnknownAccepted: Boolean,
) {
    init {
        require(requestTokenHardLimit > 0L) { "Request token hard limit must be positive." }
        require(bookTokenHardLimit >= requestTokenHardLimit) {
            "Book token hard limit must cover one request."
        }
        require(dailyTokenHardLimit >= requestTokenHardLimit) {
            "Daily token hard limit must cover one request."
        }
        require(dailyZoneId.matches(Regex("[A-Za-z0-9_+./-]{1,64}"))) {
            "Daily budget zone id is invalid."
        }
        require(priceUnknownAccepted) { "Unknown price must be explicitly accepted." }
    }

    override fun toString(): String =
        "GenerationBudgetConfirmation(priceUnknownAccepted=$priceUnknownAccepted, limits=redacted)"
}

data class GenerationStartRequest(
    val bookId: String,
    val creationSnapshotId: String,
    val creationSnapshotContentHash: String,
    val connectionId: String,
    val modelId: String,
    val normalizedDestination: String,
    val destinationProtocolId: String,
    val destinationDisclosureVersion: Int,
    val destinationBindingHash: String,
    val budget: GenerationBudgetConfirmation,
    val confirmedAt: Long,
) {
    init {
        require(
            listOf(bookId, creationSnapshotId, connectionId).all(IDENTIFIER::matches),
        ) { "Generation start identifiers are invalid." }
        require(modelId.isNotBlank() && modelId.length <= 256) {
            "Generation start model id is invalid."
        }
        require(creationSnapshotContentHash.matches(HASH)) {
            "Generation start snapshot hash is invalid."
        }
        require(normalizedDestination.matches(DESTINATION)) {
            "Generation start destination is invalid."
        }
        require(destinationProtocolId.matches(PROTOCOL)) {
            "Generation start protocol is invalid."
        }
        require(destinationDisclosureVersion > 0) {
            "Generation start disclosure version is invalid."
        }
        require(destinationBindingHash.matches(HASH)) {
            "Generation start destination binding hash is invalid."
        }
        require(confirmedAt >= 0L) { "Generation start confirmation time is invalid." }
    }

    override fun toString(): String =
        "GenerationStartRequest(disclosureVersion=$destinationDisclosureVersion, content=redacted)"

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val DESTINATION = Regex("https?://[^\\s]{1,512}")
        val PROTOCOL = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

enum class GenerationStartFailure {
    BOOK_NOT_FOUND,
    CONFIRMATION_CHANGED,
    CONNECTION_CHANGED,
    DESTINATION_CONFIRMATION_REQUIRED,
    BUDGET_CONFIRMATION_INVALID,
    START_TEMPORARILY_UNAVAILABLE,
}

sealed interface GenerationStartResult {
    data class Started(
        val bookId: String,
        val jobId: String,
        val replayed: Boolean,
    ) : GenerationStartResult {
        init {
            require(bookId.matches(IDENTIFIER) && jobId.matches(IDENTIFIER)) {
                "Generation start result identifiers are invalid."
            }
        }

        override fun toString(): String =
            "GenerationStartResult.Started(replayed=$replayed, identifiers=redacted)"
    }

    data class Failed(val reason: GenerationStartFailure) : GenerationStartResult

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

fun interface GenerationStarter {
    suspend fun start(request: GenerationStartRequest): GenerationStartResult
}

