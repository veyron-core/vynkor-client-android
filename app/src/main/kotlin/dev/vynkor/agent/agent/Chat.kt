package dev.vynkor.agent.agent

import java.util.UUID

data class ChatMessage(
    val role: String,                       // "user" | "assistant" | "error"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class Chat(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf(),
)
