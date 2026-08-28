package com.example.assistant

enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    ACTION
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val language: String? = null,
    val actionName: String? = null,
    val actionSuccess: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ArushiUiState(
    val connectionState: LiveConnectionState = LiveConnectionState.DISCONNECTED,
    val isMicMuted: Boolean = false,
    val statusMessage: String = "Tap to talk with Arushi",
    val detectedLanguage: String = "Auto Detect",
    val messages: List<ChatMessage> = emptyList(),
    val currentAssistantSpeech: String = "",
    val currentUserSpeech: String = "",
    val lastActionResult: ActionResult? = null,
    val audioVolumeLevel: Float = 0f, // 0.0f to 1.0f for visualizer
    val hasAudioPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val hasCallPermission: Boolean = false,
    val isApiKeyConfigured: Boolean = false
)
