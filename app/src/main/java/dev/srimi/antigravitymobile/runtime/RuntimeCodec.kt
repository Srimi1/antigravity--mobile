package dev.srimi.antigravitymobile.runtime

import dev.srimi.antigravitymobile.AgentItem
import org.json.JSONArray
import org.json.JSONObject

/** Private checkpoints contain only provider-neutral transcript items, never account records or headers. */
object RuntimeCodec {
    const val MAX_TRANSCRIPT = 8_000_000
    fun transcript(items: List<AgentItem>): String = JSONArray().apply {
        items.forEach { item -> put(when (item) {
            is AgentItem.User -> JSONObject().put("type", "user").put("text", item.text)
            is AgentItem.Assistant -> JSONObject().put("type", "assistant").put("text", item.text)
            is AgentItem.ToolCall -> JSONObject().put("type", "call").put("id", item.callId).put("name", item.name).put("arguments", item.arguments)
            is AgentItem.ToolResult -> JSONObject().put("type", "result").put("id", item.callId).put("output", item.output)
            is AgentItem.Opaque -> JSONObject().put("type", "opaque").put("provider", item.provider).put("json", item.json)
        }) }
    }.toString().also { require(it.length <= MAX_TRANSCRIPT) { "Task history is too large; start a new conversation" } }

    fun items(json: String): List<AgentItem> {
        require(json.length <= MAX_TRANSCRIPT)
        val array = JSONArray(json)
        require(array.length() <= 4000) { "Too many transcript items" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            when (item.getString("type")) {
                "user" -> AgentItem.User(item.getString("text"))
                "assistant" -> AgentItem.Assistant(item.getString("text"))
                "call" -> AgentItem.ToolCall(item.getString("id"), item.getString("name"), item.getString("arguments"))
                "result" -> AgentItem.ToolResult(item.getString("id"), item.getString("output"))
                "opaque" -> AgentItem.Opaque(item.getString("provider"), item.getString("json"))
                else -> error("Invalid recorded transcript item")
            }
        }
    }

    fun outcome(value: ToolOutcome): String = JSONObject().apply {
        put("type", value.status)
        when (value) {
            is ToolOutcome.Success -> put("data", value.data)
            is ToolOutcome.Failed -> { put("reason", value.reason); put("logRef", value.logRef) }
            is ToolOutcome.RuntimeUnavailable -> put("reason", value.reason)
            else -> Unit
        }
    }.toString()

    fun outcome(json: String): ToolOutcome = JSONObject(json).let { value -> when (value.getString("type")) {
        "COMPLETED" -> ToolOutcome.Success(value.getString("data"))
        "FAILED" -> ToolOutcome.Failed(value.getString("reason"), value.optString("logRef").takeIf { it.isNotEmpty() })
        "CANCELLED" -> ToolOutcome.Cancelled
        "INTERRUPTED" -> ToolOutcome.Interrupted
        "RUNTIME_UNAVAILABLE" -> ToolOutcome.RuntimeUnavailable(value.getString("reason"))
        else -> error("Invalid recorded tool outcome")
    } }
}

val ToolOutcome.status: String get() = when (this) {
    is ToolOutcome.Success -> "COMPLETED"
    is ToolOutcome.Failed -> "FAILED"
    ToolOutcome.Cancelled -> "CANCELLED"
    ToolOutcome.Interrupted -> "INTERRUPTED"
    is ToolOutcome.RuntimeUnavailable -> "RUNTIME_UNAVAILABLE"
}

fun ToolOutcome.text(): String = when (this) {
    is ToolOutcome.Success -> data
    is ToolOutcome.Failed -> "Error: $reason" + (logRef?.let { "\nRecorded log: $it" } ?: "")
    ToolOutcome.Cancelled -> "Action cancelled. No user decline was recorded. Do not retry unless the user asks."
    ToolOutcome.Interrupted -> "Action interrupted; its outcome is unconfirmed. Review recorded changes and worker results. Never replay this action."
    is ToolOutcome.RuntimeUnavailable -> "Runtime unavailable: $reason. No user decline was recorded."
}

data class RecordedExecution(val outcome: ToolOutcome, val output: String = outcome.text(), val status: String = outcome.status)
class RuntimeUnavailableException(message: String) : IllegalStateException(message)
class ModelRequestFailure(cause: Throwable) : Exception("Provider request did not finish", cause)
