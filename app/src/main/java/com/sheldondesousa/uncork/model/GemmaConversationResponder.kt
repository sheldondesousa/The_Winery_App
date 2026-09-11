package com.sheldondesousa.uncork.model

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.sheldondesousa.uncork.ui.conversation.ChatMessage
import com.sheldondesousa.uncork.ui.conversation.ConversationResponder
import com.sheldondesousa.uncork.ui.conversation.MessageAuthor
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class GemmaConversationResponder(
    context: Context,
    private val modelFile: File,
) : ConversationResponder, AutoCloseable {
    private val cacheDirectory = File(context.cacheDir, "litert-lm").apply { mkdirs() }
    private val requestMutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override suspend fun replyTo(query: String): ChatMessage = requestMutex.withLock {
        val activeConversation = ensureConversation()
        val response = buildString {
            activeConversation.sendMessageAsync(query).collect { message ->
                message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .forEach { append(it.text) }
            }
        }.trim()

        if (response.isBlank()) error("The on-device model returned an empty response.")
        val suggestion = extractSuggestion(response)
        val visibleResponse = response.toVisibleResponse()

        ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.Assistant,
            text = visibleResponse.ifBlank {
                if (suggestion != null) "I found a wine suggestion for you." else response
            },
            suggestion = suggestion,
        )
    }

    private suspend fun ensureConversation(): Conversation {
        conversation?.let { return it }
        check(modelFile.isFile) { "The on-device model file is missing." }

        return withContext(Dispatchers.Default) {
            conversation?.let { return@withContext it }

            val initializedEngine = runCatching { createEngine(Backend.GPU()) }
                .getOrElse { createEngine(Backend.CPU()) }

            val initializedConversation = initializedEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.90,
                        temperature = 0.75,
                    ),
                    maxOutputToken = 512,
                ),
            )
            engine = initializedEngine
            conversation = initializedConversation
            initializedConversation
        }
    }

    private fun createEngine(backend: Backend): Engine {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                cacheDir = cacheDirectory.absolutePath,
            ),
        )
        return try {
            candidate.initialize()
            candidate
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    companion object {
        private val WINE_PROFILE_MARKER = Regex(
            pattern = "\\[WINE_PROFILE](.+?)\\[/WINE_PROFILE]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val LEGACY_WINE_MARKER = Regex(
            pattern = "\\[WINE]\\s*(.+?)\\s*\\|\\s*(.+?)\\s*\\[/WINE]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val FENCED_JSON_BLOCK = Regex(
            pattern = "```(?:\\.?json)?\\s*([{].+?[}])\\s*```",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val JSON_OBJECT = Regex(
            pattern = "[{].+?[}]",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )

        private fun extractSuggestion(response: String): WineSuggestion? {
            val profilePayloads = buildList {
                WINE_PROFILE_MARKER.findAll(response).forEach { add(it.groupValues[1]) }
                FENCED_JSON_BLOCK.findAll(response).forEach { add(it.groupValues[1]) }
                JSON_OBJECT.findAll(response).forEach { add(it.value) }
            }
            profilePayloads.forEach { payload ->
                payload.toWineSuggestionOrNull()?.let { return it }
            }

            val match = LEGACY_WINE_MARKER.find(response) ?: return null
            val name = match.groupValues[1].trim()
            val region = match.groupValues[2].trim()
            if (name.isBlank() || region.isBlank()) return null
            return WineSuggestion(name = name, region = region)
        }

        private fun String.toVisibleResponse(): String {
            var visible = replace(WINE_PROFILE_MARKER, "")
                .replace(LEGACY_WINE_MARKER, "")

            visible = FENCED_JSON_BLOCK.replace(visible) { match ->
                if (match.groupValues[1].toWineSuggestionOrNull() != null) "" else match.value
            }
            visible = JSON_OBJECT.replace(visible) { match ->
                if (match.value.toWineSuggestionOrNull() != null) "" else match.value
            }
            return visible.trim()
        }

        private fun String.toWineSuggestionOrNull(): WineSuggestion? = runCatching {
            val json = JSONObject(trim())
            val name = json.requiredString("name")
            val region = json.requiredString("region")
            WineSuggestion(
                name = name,
                region = region,
                winery = json.knownString("winery", name),
                variety = json.knownString("variety", name),
                body = json.level("body"),
                tannin = json.level("tannin"),
                acidity = json.level("acidity"),
                flavorNotes = json.knownString("flavor_notes"),
                sourceRating = json.knownString("rating"),
                confidencePercent = json.optInt("confidence", -1).takeIf { it in 0..100 },
            )
        }.getOrNull()

        private fun JSONObject.requiredString(key: String): String =
            optString(key).trim().takeIf { it.isNotEmpty() } ?: error("Missing $key")

        private fun JSONObject.knownString(key: String, fallback: String = "Unknown"): String =
            optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: fallback

        private fun JSONObject.level(key: String): String {
            val value = knownString(key).lowercase()
            return when (value) {
                "low", "medium", "high" -> value.replaceFirstChar(Char::uppercase)
                else -> "Unknown"
            }
        }

        private const val SYSTEM_INSTRUCTION =
            "You are Uncork, a warm and concise personal sommelier. Recommend wine pairings " +
                "in casual language. Do not include cheese unless explicitly requested. Never " +
                "invent unavailable facts; say when information is uncertain. Whenever you " +
                "recommend a specific wine, finish with exactly one compact JSON object between " +
                "[WINE_PROFILE] and [/WINE_PROFILE]. Use keys name, winery, variety, region, body, " +
                "tannin, acidity, flavor_notes, rating, and confidence. Body, tannin, and acidity " +
                "must be low, medium, high, or Unknown. Rating must be a known critic or source " +
                "rating or Unknown; never invent one. Confidence is a conservative integer from " +
                "0 to 100 representing certainty in the profile, not verified accuracy. Use " +
                "Unknown for facts you cannot support. The marker is required for the app UI and " +
                "must not be explained."
    }
}
