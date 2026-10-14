package com.offlinewiki.simple.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** [LlmEngine] backed by Google's LiteRT-LM runtime and a .litertlm model file. */
class LiteRtLmEngine(
    private val modelFile: File,
    private val cacheDir: File,
    private val preferGpu: Boolean = false,
) : LlmEngine {
    private var engine: Engine? = null

    override suspend fun load() = withContext(Dispatchers.Default) {
        engine = if (preferGpu) {
            // Not every model or phone supports the GPU backend; the CPU always works.
            runCatching { start(Backend.GPU()) }.getOrElse {
                Log.w("LiteRtLmEngine", "GPU backend failed, using CPU", it)
                start(Backend.CPU())
            }
        } else {
            start(Backend.CPU())
        }
    }

    private fun start(backend: Backend): Engine {
        val created = Engine(
            EngineConfig(modelPath = modelFile.absolutePath, backend = backend, cacheDir = cacheDir.absolutePath),
        )
        try {
            created.initialize()
        } catch (e: Throwable) {
            runCatching { created.close() }
            throw e
        }
        Log.i("LiteRtLmEngine", "Loaded ${modelFile.name} on $backend")
        return created
    }

    override fun generate(prompt: String, options: GenerationOptions): Flow<String> = flow {
        val engine = engine ?: throw LlmException("The model is not loaded.")
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = options.systemInstruction?.let { Contents.of(it) },
                initialMessages = options.examples.flatMap { (user, model) ->
                    listOf(Message.user(user), Message.model(model))
                },
                samplerConfig = SamplerConfig(
                    topK = options.topK,
                    topP = options.topP,
                    temperature = options.temperature,
                ),
                maxOutputToken = options.maxOutputTokens,
            ),
        )
        var finished = false
        try {
            conversation.sendMessageAsync(prompt).collect { message ->
                val text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (text.isNotEmpty()) emit(text)
            }
            finished = true
        } finally {
            if (!finished) runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.Default)

    override fun close() {
        engine?.close()
        engine = null
    }
}
