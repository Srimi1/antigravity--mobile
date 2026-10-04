package dev.srimi.antigravitymobile.providers

import dev.srimi.antigravitymobile.AgentItem
import dev.srimi.antigravitymobile.AgentRequest
import dev.srimi.antigravitymobile.ProviderEvent
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/** A model from GET /models. Null price = the catalog does not publish one. */
data class CompatModel(
    val id: String,
    val name: String,
    val promptPrice: BigDecimal?,
    val completionPrice: BigDecimal?,
    /** From `supported_parameters` when published; null = unknown (probe decides). */
    val advertisesTools: Boolean?,
) {
    val zeroPriced: Boolean get() = promptPrice?.signum() == 0 && completionPrice?.signum() == 0
}

data class CompatUsage(val inputTokens: Long?, val outputTokens: Long?)

/** OpenAI Chat Completions mapping shared by every OpenAI-compatible provider. No credentials pass through here. */
object OpenAiCompatWire {
    fun body(model: String, request: AgentRequest, quirks: CompatQuirks, stream: Boolean = true): JSONObject = JSONObject()
        .put("model", model).put("stream", stream).put("messages", messages(request))
        .apply {
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { put(JSONObject().put("type", "function").put("function", JSONObject()
                        .put("name", it.name).put("description", it.description).put("parameters", JSONObject(it.parametersJson)))) }
                })
                put("tool_choice", "auto")
                if (quirks.parallelToolCallsParam) put("parallel_tool_calls", false)
            }
            if (stream && quirks.streamUsage) put("stream_options", JSONObject().put("include_usage", true))
        }

    /** Consecutive assistant text and tool calls form one assistant message; tool results become `tool` messages. */
    fun messages(request: AgentRequest): JSONArray {
        val out = JSONArray()
        if (request.instructions.isNotBlank()) out.put(JSONObject().put("role", "system").put("content", request.instructions))
        var assistant: JSONObject? = null
        fun flush() { assistant?.let { out.put(it) }; assistant = null }
        fun current() = assistant ?: JSONObject().put("role", "assistant").also { assistant = it }
        for (item in request.input) when (item) {
            is AgentItem.User -> { flush(); out.put(JSONObject().put("role", "user").put("content", item.text)) }
            is AgentItem.Assistant -> if (item.text.isNotEmpty()) {
                val a = current(); a.put("content", a.optString("content", "") + item.text)
            }
            is AgentItem.ToolCall -> {
                val a = current()
                if (!a.has("content")) a.put("content", JSONObject.NULL)
                (a.optJSONArray("tool_calls") ?: JSONArray().also { a.put("tool_calls", it) }).put(JSONObject().put("id", item.callId)
                    .put("type", "function").put("function", JSONObject().put("name", item.name).put("arguments", item.arguments.ifBlank { "{}" })))
            }
            is AgentItem.ToolResult -> { flush(); out.put(JSONObject().put("role", "tool").put("tool_call_id", item.callId).put("content", item.output)) }
            is AgentItem.Opaque -> Unit
        }
        flush()
        return out
    }

    fun models(json: JSONObject): List<CompatModel> {
        val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { model ->
            val id = model.optString("id").ifEmpty { model.optString("name") }.takeIf { it.isNotBlank() && it.length <= 200 } ?: return@mapNotNull null
            val pricing = model.optJSONObject("pricing")
            val params = model.optJSONArray("supported_parameters")
            CompatModel(id, model.optString("name").ifBlank { id }.take(120), price(pricing, "prompt"), price(pricing, "completion"),
                params?.let { p -> (0 until p.length()).any { p.optString(it) == "tools" } })
        }.distinctBy { it.id }.sortedBy { it.id }
    }
    private fun price(pricing: JSONObject?, key: String): BigDecimal? =
        pricing?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { BigDecimal(it) }.getOrNull() }

    fun isFree(rule: FreeRule, model: CompatModel?, id: String): Boolean = when (rule) {
        FreeRule.AllModels -> true
        is FreeRule.Listed -> id in rule.ids
        is FreeRule.Matching -> rule.pattern.containsMatchIn(id)
        is FreeRule.ZeroPriced -> model != null && model.zeroPriced && (rule.suffix == null || id.endsWith(rule.suffix))
    }
}

/** Incremental parser for Chat Completions server-sent events. */
class CompatStreamParser(private val providerLabel: String) {
    private val text = StringBuilder()
    private val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, arguments
    private val data = StringBuilder()
    var finishReason: String? = null; private set
    var done = false; private set
    var usage: CompatUsage? = null; private set

    fun line(line: String): List<ProviderEvent> {
        if (line.isEmpty()) { if (data.isEmpty()) return emptyList(); val raw = data.toString(); data.clear(); return chunk(raw.trim()) }
        if (line.startsWith("data:")) {
            if (data.length + line.length > 4_000_000) throw ProviderFailure.Unknown("$providerLabel event exceeded the size limit")
            data.append(line.removePrefix("data:").trimStart())
        }
        return emptyList()
    }

    fun finish(): List<ProviderEvent> {
        val tail = line("")
        if (finishReason == null && !done) throw ProviderFailure.StreamInterrupted("The reply from $providerLabel ended before it finished")
        val toolCalls = calls.values.filter { it.second.isNotEmpty() }.map { (id, name, args) ->
            AgentItem.ToolCall(id.toString().ifEmpty { "local-${UUID.randomUUID()}" }, name.toString(), normalize(args.toString()))
        }
        when (finishReason) {
            "length" -> if (toolCalls.isEmpty()) throw ProviderFailure.Rejected("$providerLabel's reply hit the length limit before finishing")
            "content_filter" -> throw ProviderFailure.Rejected("$providerLabel blocked the reply (content filter)")
        }
        val items = mutableListOf<ProviderEvent>()
        if (text.isNotEmpty()) items += ProviderEvent.Item(AgentItem.Assistant(text.toString()))
        toolCalls.forEach { items += ProviderEvent.Item(it) }
        return tail + items + ProviderEvent.Completed
    }

    private fun chunk(raw: String): List<ProviderEvent> {
        if (raw == "[DONE]") { done = true; return emptyList() }
        val json = runCatching { JSONObject(raw) }.getOrElse { throw ProviderFailure.Unknown("$providerLabel sent an unreadable event") }
        json.optJSONObject("error")?.let { error ->
            val code = error.opt("code")?.toString().orEmpty().take(64)
            val message = dev.srimi.antigravitymobile.SafeText.redact(error.optString("message").replace(Regex("\\s+"), " ")).take(200)
            throw FailureClassifier.http(error.optInt("status", error.optInt("code", 0)).takeIf { it in 100..599 } ?: 500,
                "$providerLabel failed during the reply" + (if (code.isNotEmpty()) " ($code)" else "") + (if (message.isNotEmpty()) " — $message" else ""), code)
        }
        json.optJSONObject("usage")?.let { u ->
            usage = CompatUsage(u.optLong("prompt_tokens", -1).takeIf { it >= 0 }, u.optLong("completion_tokens", -1).takeIf { it >= 0 })
        }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
        choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finishReason = it }
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return emptyList()
        val events = mutableListOf<ProviderEvent>()
        delta.optString("content").takeIf { delta.has("content") && !delta.isNull("content") && it.isNotEmpty() }?.let {
            text.append(it); events += ProviderEvent.Text(it)
        }
        val toolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until toolCalls.length()) {
            val call = toolCalls.optJSONObject(i) ?: continue
            val slot = calls.getOrPut(call.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
            call.optString("id").takeIf { it.isNotEmpty() && slot.first.isEmpty() }?.let { slot.first.append(it) }
            call.optJSONObject("function")?.let { f ->
                f.optString("name").takeIf { it.isNotEmpty() && slot.second.isEmpty() }?.let { slot.second.append(it) }
                if (f.has("arguments") && !f.isNull("arguments")) {
                    val args = f.get("arguments")
                    slot.third.append(if (args is JSONObject) args.toString() else args.toString())
                }
            }
        }
        return events
    }

    private fun normalize(raw: String): String = runCatching { JSONObject(raw.ifBlank { "{}" }).toString() }.getOrDefault("{}")
}
