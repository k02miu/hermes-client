package io.github.k02miu.hermesmobile.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Tool calls arrive as raw JSON: the call's arguments (`{"command": "git status"}`) and its result
 * (`{"output": "...", "exit_code": 0}` for terminal). These turn them into what a reader wants to see.
 */

private val pretty = Json { prettyPrint = true }

/** The arguments as an object, whether they came as one (live events) or as a JSON string (history). */
fun toolArgs(raw: String): JsonObject? = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

/** The argument that says what a call does, in one line: the command, the code's first line, the path... */
fun toolSummary(name: String, args: String): String {
    val o = toolArgs(args) ?: return args.oneLine()
    for (key in SUMMARY_KEYS) {
        val value = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
        return if (key == "code") value.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim() else value.oneLine()
    }
    return o.entries.joinToString("  ") { (k, v) -> "$k=" + ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }.oneLine()
}

private val SUMMARY_KEYS = listOf("command", "code", "path", "file_path", "url", "query", "pattern", "name", "goal", "prompt", "text")

/** The main input in full when it is code or a command, else the arguments laid out. */
fun toolInput(args: String): Pair<String, String> {
    val o = toolArgs(args) ?: return "入力" to args
    (o["command"] as? JsonPrimitive)?.contentOrNull?.let { return "コマンド" to it }
    (o["code"] as? JsonPrimitive)?.contentOrNull?.let { return "コード" to it }
    return "入力" to pretty.encodeToString(JsonObject.serializer(), o)
}

/** Readable output: terminal's `output` (and the error, if any) as text, anything else laid out. */
fun toolOutput(raw: JsonElement?): String? {
    val value = unwrap(raw) ?: return null
    if (value is JsonObject) {
        val text = listOf("output", "content", "result", "stdout").firstNotNullOfOrNull { (value[it] as? JsonPrimitive)?.contentOrNull }
        val error = (value["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (value["stderr"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (text != null || error != null) {
            val exit = (value["exit_code"] as? JsonPrimitive)?.intOrNull
            return buildString {
                text?.let { append(it) }
                if (error != null) { if (isNotEmpty()) append("\n\n"); append(error) }
                if (exit != null && exit != 0) { if (isNotEmpty()) append("\n\n"); append("exit code $exit") }
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), value)
    }
    if (value is JsonArray) return pretty.encodeToString(JsonArray.serializer(), value)
    return (value as? JsonPrimitive)?.contentOrNull
}

/** A non-zero exit, an error field or `success: false`. */
fun toolFailed(raw: JsonElement?): Boolean {
    val o = unwrap(raw) as? JsonObject ?: return false
    val exit = (o["exit_code"] as? JsonPrimitive)?.intOrNull
    val error = when (val e = o["error"]) {
        null, JsonNull -> false
        is JsonPrimitive -> e.booleanOrNull ?: e.content.isNotBlank()
        else -> true
    }
    return (exit != null && exit != 0) || error || (o["success"] as? JsonPrimitive)?.booleanOrNull == false
}

/** History stores the result as a JSON string; parse it when it is one. */
private fun unwrap(raw: JsonElement?): JsonElement? {
    if (raw == null || raw is JsonNull) return null
    val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return raw
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return raw
    return runCatching { Json.parseToJsonElement(text) }.getOrDefault(raw)
}

private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()

/** "8 分", "1 時間 5 分": for durations and process uptimes. */
fun describeSeconds(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s < 60 -> if (seconds < 10) "%.1f 秒".format(seconds) else "$s 秒"
        s < 3600 -> "${s / 60} 分"
        else -> "${s / 3600} 時間 ${s % 3600 / 60} 分"
    }
}
