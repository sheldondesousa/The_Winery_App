package com.sheldondesousa.uncork.model

import org.json.JSONObject

enum class CoverageStatus(val wireValue: String) {
    Clarify("clarify"),
    Closed("closed"),
}

enum class CoverageEvent(val wireValue: String) {
    Answer("answer"),
    Digression("digression"),
    OffDomain("off_domain"),
}

/** Whether the user has agreed to run the search with whatever preferences are resolved so far. */
enum class ConfirmationStatus(val wireValue: String) {
    Pending("pending"),
    Yes("yes"),
    No("no"),
}

data class WineFieldCoverage(
    val q1Type: CoverageStatus = CoverageStatus.Clarify,
    val q2Country: CoverageStatus = CoverageStatus.Clarify,
    val q3Attributes: CoverageStatus = CoverageStatus.Clarify,
    val event: CoverageEvent = CoverageEvent.Answer,
    val confirmed: ConfirmationStatus = ConfirmationStatus.Pending,
) {
    val allClosed: Boolean
        get() = q1Type == CoverageStatus.Closed &&
            q2Country == CoverageStatus.Closed &&
            q3Attributes == CoverageStatus.Closed

    /** Enough to search: any one field closed (a resolved answer, or an explicit no-preference). */
    val readyToSearch: Boolean
        get() = q1Type == CoverageStatus.Closed ||
            q2Country == CoverageStatus.Closed ||
            q3Attributes == CoverageStatus.Closed

    /** True once ready to search but the user has not yet said go ahead. */
    val awaitingGoAhead: Boolean
        get() = readyToSearch && confirmed != ConfirmationStatus.Yes

    val pendingQuestion: String
        get() = when {
            q1Type != CoverageStatus.Closed -> "Q1 wine type"
            q2Country != CoverageStatus.Closed -> "Q2 country"
            q3Attributes != CoverageStatus.Closed -> "Q3 body, tannin, acidity, or flavor"
            else -> "none; all questions are closed"
        }

    fun toCompactJson(): String = JSONObject()
        .put("q1_type", q1Type.wireValue)
        .put("q2_country", q2Country.wireValue)
        .put("q3_attributes", q3Attributes.wireValue)
        .put("event", event.wireValue)
        .put("confirmed", confirmed.wireValue)
        .toString()

    companion object {
        fun fromJsonOrNull(json: String): WineFieldCoverage? = runCatching {
            val objectValue = JSONObject(json)
            WineFieldCoverage(
                q1Type = objectValue.coverageStatus("q1_type"),
                q2Country = objectValue.coverageStatus("q2_country"),
                q3Attributes = objectValue.coverageStatus("q3_attributes"),
                event = objectValue.coverageEvent("event"),
                confirmed = objectValue.confirmationStatus(),
            )
        }.getOrNull()

        private fun JSONObject.coverageStatus(key: String): CoverageStatus {
            val value = getString(key)
            return CoverageStatus.entries.firstOrNull { it.wireValue.equals(value, true) }
                ?: error("Invalid coverage status for $key")
        }

        private fun JSONObject.coverageEvent(key: String): CoverageEvent {
            val value = getString(key)
            return CoverageEvent.entries.firstOrNull { it.wireValue.equals(value, true) }
                ?: error("Invalid coverage event")
        }

        // Older responses (and hand-written test payloads) omit "confirmed"; treat that as pending.
        private fun JSONObject.confirmationStatus(): ConfirmationStatus {
            val value = optString("confirmed", ConfirmationStatus.Pending.wireValue)
            return ConfirmationStatus.entries.firstOrNull { it.wireValue.equals(value, true) }
                ?: ConfirmationStatus.Pending
        }
    }
}
