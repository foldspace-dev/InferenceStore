package dev.mattramotar.inferencestore.provider.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI-compatible `/chat/completions` request body (only the MVP fields). */
@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = true,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
internal data class WireMessage(val role: String, val content: String)

@Serializable
internal data class ResponseFormat(val type: String)

/** A single streamed `data:` chunk. */
@Serializable
internal data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: WireUsage? = null,
)

@Serializable
internal data class StreamChoice(
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class Delta(val content: String? = null)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)
