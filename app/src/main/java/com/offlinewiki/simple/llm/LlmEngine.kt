package com.offlinewiki.simple.llm

import kotlinx.coroutines.flow.Flow

data class GenerationOptions(
    val systemInstruction: String? = null,
    /** Example exchanges (user message to model reply) shown to the model before the prompt. */
    val examples: List<Pair<String, String>> = emptyList(),
    val temperature: Double = 0.2,
    val topK: Int = 20,
    val topP: Double = 0.9,
    val maxOutputTokens: Int = 512,
)

/**
 * A text-generation model running on this device. The rest of the app only
 * talks to this interface, so the runtime or the model can be swapped by
 * providing another implementation to [LlmManager].
 */
interface LlmEngine {
    /** Loads the model into memory. Slow; never call on the main thread. */
    suspend fun load()

    /**
     * Starts a fresh, single-turn generation and streams the reply as pieces of
     * text. Cancelling the collector stops the generation.
     */
    fun generate(prompt: String, options: GenerationOptions): Flow<String>

    /** Frees the model's memory. */
    fun close()
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
