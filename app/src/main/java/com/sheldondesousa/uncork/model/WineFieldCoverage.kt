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

data class WineFieldCoverage(
    val q1Type: CoverageStatus = CoverageStatus.Clarify,
    val q2Country: CoverageStatus = CoverageStatus.Clarify,
    val q3Attributes: CoverageStatus = CoverageStatus.Clarify,
    val event: CoverageEvent = CoverageEvent.Answer,
) {
    val allClosed: Boolean
        get() = q1Type == CoverageStatus.Closed &&
            q2Country == CoverageStatus.Closed &&
            q3Attributes == CoverageStatus.Closed

    fun toCompactJson(): String = JSONObject()
        .put("q1_type", q1Type.wireValue)
        .put("q2_country", q2Country.wireValue)
        .put("q3_attributes", q3Attributes.wireValue)
        .put("event", event.wireValue)
        .toString()

    companion object {
        fun fromJsonOrNull(json: String): WineFieldCoverage? = runCatching {
            val objectValue = JSONObject(json)
            WineFieldCoverage(
                q1Type = objectValue.coverageStatus("q1_type"),
                q2Country = objectValue.coverageStatus("q2_country"),
                q3Attributes = objectValue.coverageStatus("q3_attributes"),
                event = objectValue.coverageEvent("event"),
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
    }
}
