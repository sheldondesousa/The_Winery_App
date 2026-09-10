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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

        ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.Assistant,
            text = response,
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
        private const val SYSTEM_INSTRUCTION =
            "You are Uncork, a warm and concise personal sommelier. Recommend wine pairings " +
                "in casual language. Do not include cheese unless explicitly requested. Never " +
                "invent unavailable facts; say when information is uncertain."
    }
}
