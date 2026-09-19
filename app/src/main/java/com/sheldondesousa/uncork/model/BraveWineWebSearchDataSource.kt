package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class BraveSearchResult(
    val title: String,
    val url: String,
    val description: String,
)

fun interface BraveSearchClient {
    suspend fun search(query: String, limit: Int): List<BraveSearchResult>
}

fun interface WineWebResultSynthesizer {
    suspend fun synthesize(
        request: WineWebSearchRequest,
        results: List<BraveSearchResult>,
    ): List<WineSuggestion>
}

class BraveWineWebSearchDataSource(
    private val client: BraveSearchClient,
    private val synthesizer: WineWebResultSynthesizer,
) : WineWebSearchDataSource {
    override suspend fun search(request: WineWebSearchRequest): List<WineSuggestion> {
        val results = client.search(request.toSearchQuery(), SEARCH_RESULT_LIMIT)
        if (results.isEmpty()) return emptyList()
        return synthesizer.synthesize(request, results).take(request.limit).also { options ->
            logFlow("Brave results=${results.size}, synthesized=${options.size}")
        }
    }

    private fun WineWebSearchRequest.toSearchQuery(): String = buildList {
        add(originalQuery)
        gemmaSuggestions.forEach { suggestion ->
            addAll(
                listOf(
                    suggestion.name,
                    suggestion.variety,
                    suggestion.province,
                    suggestion.country,
                ).filter { value -> value.isNotBlank() && !value.equals("Unknown", true) },
            )
        }
        add("specific wine bottle winery recommendations")
    }.distinctBy { it.trim().lowercase() }.joinToString(" ")

    private companion object {
        const val SEARCH_RESULT_LIMIT = 3
    }
}

class BraveSearchHttpClient(
    private val apiKey: String,
) : BraveSearchClient {
    override suspend fun search(query: String, limit: Int): List<BraveSearchResult> =
        withContext(Dispatchers.IO) {
            check(apiKey.isNotBlank()) { "Brave Search API key is not configured." }
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val endpoint = URI(
                "https://api.search.brave.com/res/v1/web/search" +
                    "?q=$encodedQuery&count=${limit.coerceIn(1, 20)}&search_lang=en" +
                    "&extra_snippets=true",
            ).toURL()
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Subscription-Token", apiKey)
            }
            try {
                check(connection.responseCode in 200..299) {
                    "Brave Search failed with HTTP ${connection.responseCode}."
                }
                parseResults(connection.inputStream.bufferedReader().use { it.readText() })
                    .take(limit)
            } finally {
                connection.disconnect()
            }
        }

    internal fun parseResults(response: String): List<BraveSearchResult> = runCatching {
        val results = JSONObject(response)
            .optJSONObject("web")
            ?.optJSONArray("results")
            ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                val title = result.optString("title").trim().stripHtml()
                val url = result.optString("url").trim()
                val description = result.optString("description").trim()
                val extraSnippets = result.optJSONArray("extra_snippets")
                val evidence = buildList {
                    description.takeIf(String::isNotBlank)?.let(::add)
                    if (extraSnippets != null) {
                        for (snippetIndex in 0 until extraSnippets.length()) {
                            extraSnippets.optString(snippetIndex)
                                .trim()
                                .takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }.distinct().joinToString(" ").stripHtml().take(MAX_EVIDENCE_CHARS_PER_RESULT)
                if (title.isNotBlank() && url.isNotBlank()) {
                    add(BraveSearchResult(title, url, evidence))
                }
            }
        }
    }.getOrDefault(emptyList())

    // Raw Brave markup/entities bloat the evidence prompt enough to blow the on-device model's output budget.
    private fun String.stripHtml(): String = replace(HTML_TAG, "")
        .replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&quot;", "\"").replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val MAX_EVIDENCE_CHARS_PER_RESULT = 300
        val HTML_TAG = Regex("<[^>]*>")
    }
}
