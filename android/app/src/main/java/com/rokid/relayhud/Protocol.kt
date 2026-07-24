package com.rokid.relayhud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class RunSummary(val id: String, val status: String, val prompt: String)

sealed interface AgentEvent {
    data class System(val sessionId: String?) : AgentEvent
    object Thinking : AgentEvent
    data class Text(val delta: String) : AgentEvent
    data class ToolUse(val id: String, val name: String, val summary: String) : AgentEvent
    data class ToolResult(val id: String, val isError: Boolean) : AgentEvent
    object Done : AgentEvent
    data class Error(val message: String) : AgentEvent
    object Unknown : AgentEvent
}

sealed interface ServerMessage {
    data class Sync(val sessionId: String?, val currentRun: RunSummary?) : ServerMessage
    data class Event(val runId: String, val seq: Int, val event: AgentEvent) : ServerMessage
    data class RunEnd(val runId: String, val status: String) : ServerMessage
    data class Transcript(val text: String) : ServerMessage
    data class PermissionRequest(val id: String, val summary: String, val options: List<String>, val allowKey: String, val timeoutChoice: String) : ServerMessage
    data class Usage(val model: String?, val costUsd: Double, val tokens: Long) : ServerMessage
    data class ModelRequest(val id: String, val options: List<String>, val current: Int, val timeoutChoice: String) : ServerMessage
    data class PhotoAck(val file: String) : ServerMessage
    object Unknown : ServerMessage
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.lng(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun summarizeInput(input: JsonObject?): String {
    if (input == null) return ""
    for (k in listOf("file_path", "command", "pattern", "path", "query")) {
        input.str(k)?.let { return it }
    }
    return ""
}

private fun parseEvent(o: JsonObject): AgentEvent = when (o.str("type")) {
    "system" -> AgentEvent.System(o.str("sessionId"))
    "thinking" -> AgentEvent.Thinking
    "text" -> AgentEvent.Text(o.str("delta") ?: "")
    "tool_use" -> AgentEvent.ToolUse(
        id = o.str("id") ?: "",
        name = o.str("name") ?: "",
        summary = summarizeInput(o["input"] as? JsonObject)
    )
    "tool_result" -> AgentEvent.ToolResult(id = o.str("id") ?: "", isError = o.bool("isError") ?: false)
    "done" -> AgentEvent.Done
    "error" -> AgentEvent.Error(o.str("message") ?: "")
    else -> AgentEvent.Unknown
}

/** 解析中继 server→client 消息;任何异常/未知 → Unknown,不抛。 */
fun parseServerMessage(text: String): ServerMessage = try {
    val o = json.parseToJsonElement(text).jsonObject
    when (o.str("type")) {
        "sync" -> {
            val cr = (o["currentRun"] as? JsonObject)?.let {
                RunSummary(it.str("id") ?: "", it.str("status") ?: "", it.str("prompt") ?: "")
            }
            ServerMessage.Sync(o.str("sessionId"), cr)
        }
        "event" -> ServerMessage.Event(
            runId = o.str("runId") ?: "",
            seq = o.int("seq") ?: 0,
            event = parseEvent((o["event"] as? JsonObject) ?: JsonObject(emptyMap()))
        )
        "runEnd" -> ServerMessage.RunEnd(o.str("runId") ?: "", o.str("status") ?: "")
        "transcript" -> ServerMessage.Transcript(o.str("text") ?: "")
        "permissionRequest" -> {
            val opts = (o["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            ServerMessage.PermissionRequest(o.str("id") ?: "", o.str("summary") ?: "", opts, o.str("allowKey") ?: "", o.str("timeoutChoice") ?: (opts.lastOrNull() ?: ""))
        }
        "usage" -> ServerMessage.Usage(o.str("model"), o.dbl("costUsd") ?: 0.0, o.lng("tokens") ?: 0L)
        "modelRequest" -> {
            val opts = (o["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            ServerMessage.ModelRequest(o.str("id") ?: "", opts, o.int("current") ?: 0, o.str("timeoutChoice") ?: (opts.lastOrNull() ?: ""))
        }
        "photoAck" -> ServerMessage.PhotoAck(o.str("file") ?: "")
        else -> ServerMessage.Unknown
    }
} catch (_: Exception) {
    ServerMessage.Unknown
}
