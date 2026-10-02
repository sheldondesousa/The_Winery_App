package com.sheldondesousa.uncork.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Eval-only side channel. When [active] is null (always, outside the debug eval runner) every hook
 * is a no-op and production behavior is untouched. The eval runner sets [active] around one Gemma
 * call to capture the exact request, Gemma's raw text, and each repair Kotlin applies afterwards.
 */
internal object GemmaEvalTrace {
    class Call {
        var systemInstruction = ""
        var userMessage = ""
        val raw = StringBuilder()
        var phase = "request"
        val repairs = JSONArray()
        var parsedCards = 0
        var parseFailures = 0
        var parseOk: Boolean? = null

        fun repair(kind: String, field: String, detail: String) {
            repairs.put(JSONObject().put("phase", phase).put("kind", kind).put("field", field).put("detail", detail))
        }

        fun toJson(): JSONObject = JSONObject()
            .put("request_sent", JSONObject().put("system_instruction", systemInstruction).put("user_message", userMessage))
            .put("raw_response", raw.toString())
            .put("repair_log", repairs)
            .put("parse_ok", parseOk ?: false)
            .put("parsed_cards", parsedCards)
            .put("parse_failures", parseFailures)
    }

    @Volatile
    var active: Call? = null

    fun begin(): Call = Call().also { active = it }

    fun end(): Call? = active.also { active = null }
}
