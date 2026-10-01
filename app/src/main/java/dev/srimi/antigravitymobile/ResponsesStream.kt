package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.providers.FailureClassifier
import dev.srimi.antigravitymobile.providers.ProviderFailure
import org.json.JSONArray
import org.json.JSONObject

/**
 * Incremental parser for the documented Responses API server-sent event stream. Feeds one line at a time.
 * Error messages carry only event types and short error codes, never provider bodies or prompts.
 */
class ResponsesStreamParser(private val provider: String = ResponsesWire.PROVIDER) {
    var completed = false
        private set
    private val data = StringBuilder()

    fun line(line: String): List<ProviderEvent> {
        if (line.isEmpty()) {
            if (data.isEmpty()) return emptyList()
            val raw = data.toString().trimEnd()
            data.clear()
            return event(raw)
        }
        if (line.startsWith("data:")) {
            if (data.length + line.length >= 4_000_000) throw ProviderFailure.Unknown("Provider event exceeded the size limit")
            data.append(line.removePrefix("data:").trimStart()).append('\n')
        }
        return emptyList()
    }
    fun finish(): List<ProviderEvent> = line("")

    private fun event(raw: String): List<ProviderEvent> {
        if (raw == "[DONE]") return emptyList()
        val event = JSONObject(raw)
        return when (event.optString("type")) {
            "response.output_text.delta" -> listOf(ProviderEvent.Text(event.getString("delta")))
            "response.output_item.done" -> listOfNotNull(ResponsesWire.item(event.getJSONObject("item"), provider)?.let(ProviderEvent::Item))
            "response.completed" -> { completed = true; listOf(ProviderEvent.Completed) }
            "response.failed", "response.incomplete", "error" -> throw ResponsesWire.streamFailure(event.optString("type"), code(event).removePrefix(": "),
                "Provider did not complete the turn (${event.optString("type")}${code(event)}). Check account access and usage limits.")
            else -> emptyList()
        }
    }
    private fun code(event: JSONObject): String {
        val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
        val value = error?.optString("code").orEmpty().ifEmpty { event.optString("code") }
            .ifEmpty { event.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason").orEmpty() }
        return if (value.matches(Regex("[A-Za-z0-9_.-]{1,48}"))) ": $value" else ""
    }
}

data class ChatModel(val slug: String, val displayName: String, val listed: Boolean)

object ResponsesWire {
    const val PROVIDER = "chatgpt"

    /** Parses the model catalog. Models marked `visibility: list` are the ones OpenAI recommends showing. */
    fun catalog(json: org.json.JSONObject): List<ChatModel> {
        val models = json.optJSONArray("models") ?: json.optJSONArray("data") ?: JSONArray()
        return (0 until models.length()).map { models.getJSONObject(it) }.mapNotNull { model ->
            val slug = model.optString("slug").ifEmpty { model.optString("id") }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChatModel(slug, model.optString("display_name").ifEmpty { slug }, model.optString("visibility", "list") == "list")
        }.distinctBy { it.slug }.sortedBy { if (it.listed) 0 else 1 }
    }

    /**
     * Handles a successful response that is not labelled as an event stream. Event-stream text is parsed anyway;
     * a complete Response object is accepted only if it says it completed; anything else becomes a short,
     * credential-free explanation (status, content type, provider error code and message).
     */
    fun unlabelled(status: Int, contentType: String, body: String): List<ProviderEvent> {
        val text = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (text.startsWith("data:") || text.startsWith("event:") || text.startsWith(":")) {
            val parser = ResponsesStreamParser()
            val events = text.lineSequence().flatMap { parser.line(it.trimEnd('\r')) }.toMutableList()
            if (!parser.completed) events += parser.finish()
            if (parser.completed) return events
            throw ProviderFailure.StreamInterrupted("The stream from OpenAI ended before the response completed")
        }
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (json != null && json.optString("object") == "response" && json.optJSONObject("error") == null) {
            if (json.optString("status") != "completed") throw ProviderFailure.Unknown(
                "OpenAI did not complete the response (status ${json.optString("status").take(32).ifEmpty { "unknown" }})")
            val output = json.optJSONArray("output") ?: JSONArray()
            return (0 until output.length()).mapNotNull { item(output.getJSONObject(it))?.let(ProviderEvent::Item) } + ProviderEvent.Completed
        }
        throw ProviderFailure.Unknown(describe(status, contentType, text))
    }

    /** Typed failure for an unsuccessful HTTP reply (any OpenAI-compatible server). */
    fun failure(status: Int, contentType: String, body: String, retryAfter: String? = null): ProviderFailure {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
        val error = json?.optJSONObject("error") ?: json?.optJSONObject("response")?.optJSONObject("error")
        val code = (error?.optString("code").orEmpty().ifEmpty { error?.optString("type").orEmpty() }).take(64)
        return FailureClassifier.http(status, describe(status, contentType, body), code, FailureClassifier.retryAfter(retryAfter))
    }

    /** Typed failure for a failed/incomplete stream event. */
    fun streamFailure(type: String, code: String, message: String): ProviderFailure = when {
        Regex("(?i)insufficient_quota|quota").containsMatchIn(code) -> ProviderFailure.QuotaExhausted(message)
        Regex("(?i)rate_limit").containsMatchIn(code) -> ProviderFailure.RateLimited(message)
        Regex("(?i)trial").containsMatchIn(code) -> ProviderFailure.TrialExpired(message)
        type == "response.incomplete" || Regex("(?i)content_filter|max_output_tokens|invalid").containsMatchIn(code) -> ProviderFailure.Rejected(message)
        else -> ProviderFailure.Unknown(message)
    }

    /** A short explanation of an unusable provider reply. Never includes headers, tokens or the request. */
    fun describe(status: Int, contentType: String, body: String): String {
        val type = contentType.substringBefore(';').trim().take(60).ifEmpty { "no content type" }
        val json = runCatching { org.json.JSONObject(body.trim()) }.getOrNull()
        val error = json?.optJSONObject("error") ?: json?.optJSONObject("response")?.optJSONObject("error")
        if (error != null || json?.has("detail") == true) {
            val code = (error?.optString("code").orEmpty().ifEmpty { error?.optString("type").orEmpty() })
                .takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
            val message = (error?.optString("message") ?: json?.optString("detail")).orEmpty()
                .replace(Regex("\\s+"), " ").replace(Regex("(?i)bearer\\s+\\S+|sk-[A-Za-z0-9_-]+|eyJ[A-Za-z0-9_.-]+"), "[redacted]").take(200)
            return "OpenAI replied HTTP $status ($type)" + (code?.let { ": $it" } ?: "") + (if (message.isNotBlank()) " — $message" else "")
        }
        return when {
            type.contains("html") -> "Received a web page (HTTP $status) instead of an OpenAI response. A network filter, " +
                "captive Wi-Fi login, VPN or proxy may be intercepting api.openai.com. Try mobile data or another network."
            body.isBlank() -> "OpenAI replied HTTP $status with an empty $type body"
            else -> "OpenAI replied HTTP $status with $type instead of an event stream"
        }
    }

    /** Maps a finished output item. Unknown item types are ignored rather than guessed. */
    fun item(item: JSONObject, provider: String = PROVIDER): AgentItem? = when (item.optString("type")) {
        "message" -> {
            val content = item.optJSONArray("content") ?: JSONArray()
            val text = (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "output_text" || it.optString("type") == "refusal" }
                .joinToString("") { it.optString("text", it.optString("refusal")) }
            AgentItem.Assistant(text)
        }
        "function_call" -> AgentItem.ToolCall(item.getString("call_id"), item.getString("name"), item.optString("arguments", "{}"))
        "reasoning" -> AgentItem.Opaque(provider, JSONObject(item.toString()).apply { remove("id") }.toString())
        else -> null
    }

    /** Stateless (`store: false`) input: every prior item is replayed, without server item IDs. */
    fun input(items: List<AgentItem>, provider: String = PROVIDER): JSONArray = JSONArray().apply {
        items.forEach { item ->
            when (item) {
                is AgentItem.User -> put(JSONObject().put("role", "user").put("content", item.text))
                is AgentItem.Assistant -> if (item.text.isNotEmpty()) put(JSONObject().put("role", "assistant").put("content", item.text))
                is AgentItem.ToolCall -> put(JSONObject().put("type", "function_call").put("call_id", item.callId)
                    .put("name", item.name).put("arguments", item.arguments))
                is AgentItem.ToolResult -> put(JSONObject().put("type", "function_call_output").put("call_id", item.callId)
                    .put("output", item.output))
                is AgentItem.Opaque -> if (item.provider == provider) put(JSONObject(item.json))
            }
        }
    }

    fun tools(specs: List<ToolSpec>): JSONArray = JSONArray().apply {
        specs.forEach { spec ->
            put(JSONObject().put("type", "function").put("name", spec.name).put("description", spec.description)
                .put("parameters", JSONObject(spec.parametersJson)).put("strict", false))
        }
    }

    /** [encryptedReasoning] asks reasoning models to return replayable state; some models reject it. */
    fun body(model: String, request: AgentRequest, encryptedReasoning: Boolean = true): JSONObject = JSONObject()
        .put("model", model).put("store", false).put("stream", true)
        .put("input", input(request.input))
        .apply {
            if (request.instructions.isNotBlank()) put("instructions", request.instructions)
            if (request.tools.isNotEmpty()) {
                put("tools", tools(request.tools)).put("tool_choice", "auto").put("parallel_tool_calls", false)
            }
            if (encryptedReasoning) put("include", JSONArray().put("reasoning.encrypted_content"))
        }
}
