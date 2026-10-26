package io.github.k02miu.hermesmobile.ui

import io.github.k02miu.hermesmobile.data.StoredMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * What a session pane shows, read from the gateway's wire shapes: the stored history page
 * (`/api/sessions/{id}/messages`) and the background process list (`process.list`).
 */

/**
 * The transcript rows of a history page. Tool results are rows of their own (role=tool), tied to their call by id;
 * while the turn is [running], a call in the last assistant message with no result yet is still running
 * (a `sleep 270; check…` wait, say).
 */
internal fun historyItems(messages: List<StoredMessage>, running: Boolean): List<ChatItem> {
    val results = messages.filter { it.role == "tool" && it.toolCallId != null }.associate { it.toolCallId!! to it.content }
    val lastCalls = messages.lastOrNull { it.role == "assistant" && it.toolCalls is JsonArray }?.id
    return messages.flatMap { toItems(it, results, runningCalls = running && it.id == lastCalls) }
}

private fun toItems(m: StoredMessage, results: Map<String, JsonElement?>, runningCalls: Boolean): List<ChatItem> {
    if (m.displayKind == "hidden") return emptyList()
    val key = "m-${m.id}"
    return when (m.role) {
        "user" -> listOfNotNull(textOf(m)?.let { ChatItem.User(key, it) })
        "assistant" -> buildList {
            textOf(m)?.let { add(ChatItem.Assistant(key, it)) }
            (m.toolCalls as? JsonArray)?.forEachIndexed { i, call ->
                val fn = (call as? JsonObject)?.get("function") as? JsonObject
                val name = fn?.str("name") ?: return@forEachIndexed
                val callId = (call as JsonObject).str("id")
                val result = callId?.let(results::get)
                val state = when {
                    runningCalls && callId !in results -> ToolState.Running
                    toolFailed(result) -> ToolState.Failed
                    else -> ToolState.Done
                }
                add(ChatItem.Tool("$key-t$i", name, fn.str("arguments").orEmpty(), state, output = toolOutput(result), startedAt = m.timestamp))
            }
        }
        else -> emptyList()
    }
}

private fun textOf(m: StoredMessage): String? {
    m.displayContent?.takeIf { it.isNotBlank() }?.let { return it }
    return when (val c = m.content) {
        is JsonPrimitive -> c.contentOrNull
        is JsonArray -> c.mapNotNull { part -> (part as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("\n")
        else -> null
    }?.takeIf { it.isNotBlank() }
}

/** Background `terminal` calls seen in history: command → (notify when done, output patterns that wake Hermes). */
internal fun backgroundCalls(items: List<ChatItem>): Map<String, Pair<Boolean, List<String>>> =
    items.filterIsInstance<ChatItem.Tool>().filter { it.name == "terminal" }.mapNotNull { tool ->
        val a = toolArgs(tool.args) ?: return@mapNotNull null
        if (a.bool("background") != true) return@mapNotNull null
        val command = a.str("command") ?: return@mapNotNull null
        val notify = a.bool("notify") == true || a.bool("notify_on_complete") == true
        val patterns = (a["watch_patterns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        command to (notify to patterns)
    }.toMap()

/**
 * A `process.list` result, running ones first and newest first. The live gateway's rows may leave out the
 * wake-up flags and the uptime; [wakeCalls] (from [backgroundCalls]) and `started_epoch` fill them in.
 */
internal fun parseProcesses(result: JsonObject, wakeCalls: Map<String, Pair<Boolean, List<String>>>, now: Double): List<BackgroundProcess> =
    (result["processes"] as? JsonArray)?.mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        val command = o.str("command").orEmpty()
        // The registry cuts commands at 200 characters.
        val call = wakeCalls.entries.firstOrNull { (cmd, _) -> command.isNotEmpty() && cmd.startsWith(command.take(200)) }?.value
        BackgroundProcess(
            id = o.str("session_id") ?: return@mapNotNull null,
            command = command,
            status = o.str("status") ?: "running",
            startedAt = o["started_epoch"]?.jsonPrimitive?.doubleOrNull
                ?: o["uptime_seconds"]?.jsonPrimitive?.longOrNull?.let { now - it },
            notifyOnComplete = o.bool("notify_on_complete") == true || call?.first == true,
            watchPatterns = (o["watch_patterns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: call?.second.orEmpty(),
            output = o.str("output_tail") ?: o.str("output_preview").orEmpty(),
            exitCode = o["exit_code"]?.jsonPrimitive?.intOrNull,
        )
    }.orEmpty().sortedWith(compareByDescending<BackgroundProcess> { it.running }.thenByDescending { it.startedAt ?: 0.0 })

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.choices(): List<String> = (this["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
