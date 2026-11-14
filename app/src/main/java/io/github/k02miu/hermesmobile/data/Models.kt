package io.github.k02miu.hermesmobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Me(
    val provider: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class Profile(
    val name: String,
    @SerialName("is_default") val isDefault: Boolean = false,
    val model: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("bot_title") val botTitle: String = "",
    val description: String = "",
) {
    val label: String get() = botTitle.ifBlank { displayName.ifBlank { name } }
}

@Serializable
data class StoredMessage(
    val id: Long? = null,
    val role: String = "",
    val content: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("display_content") val displayContent: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
    @SerialName("tool_calls") val toolCalls: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val timestamp: Double? = null,
)

@Serializable
data class MessagesResponse(
    @SerialName("session_id") val sessionId: String = "",
    val messages: List<StoredMessage> = emptyList(),
)

@Serializable
data class ProfilesResponse(val profiles: List<Profile> = emptyList())

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val source: String = "",
    val preview: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("last_activity_at") val lastActivityAt: Double? = null,
    val profile: String = "",
    val unread: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = false,
    val pinned: Boolean = false,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: preview?.takeIf { it.isNotBlank() } ?: "（無題）"
    val activeAt: Double get() = lastActive ?: lastActivityAt ?: 0.0
}

@Serializable
data class SessionSlice(val sessions: List<SessionSummary> = emptyList())

@Serializable
data class SidebarResponse(val recents: SessionSlice = SessionSlice())

@Serializable
data class MutationSnapshot(
    @SerialName("runtime_revision") val runtimeRevision: Long = 0,
    /** The generation of the turn that is (or was last) running; stopping a turn has to name it. */
    @SerialName("runtime_generation") val runtimeGeneration: Long = 0,
)

@Serializable
data class WsTicket(val ticket: String, @SerialName("ttl_seconds") val ttlSeconds: Int = 30)

/**
 * `GET /api/sessions/{id}`, for the little that says whether a turn is running. Hermes writes what it is doing
 * (`receiving stream response`, a tool's name) into the session while a turn runs and clears it when the turn
 * ends, whichever Hermes process runs it; the time stays.
 */
@Serializable
data class SessionActivity(
    @SerialName("last_activity_at") val lastActivityAt: Double? = null,
    @SerialName("last_activity_description") val description: String? = null,
) {
    /** What the running turn is doing, or null when none is. A label older than [staleAfter] is a crashed turn's. */
    fun running(now: Double, staleAfter: Double = 900.0): String? =
        description?.takeIf { it.isNotBlank() && now - (lastActivityAt ?: 0.0) < staleAfter }
}

/** `GET /api/files/read`: a gateway-local file as a base64 data URL. */
@Serializable
data class FileReadResponse(
    val name: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("data_url") val dataUrl: String = "",
)
