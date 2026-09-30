package dev.srimi.antigravitymobile

import org.json.JSONArray
import org.json.JSONObject

class ProviderFailure(message: String) : Exception(message)

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
            if (data.length + line.length >= 4_000_000) throw ProviderFailure("Provider event exceeded the size limit")
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
            "response.failed", "response.incomplete", "error" -> throw ProviderFailure(
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

object ResponsesWire {
    const val PROVIDER = "chatgpt"

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
