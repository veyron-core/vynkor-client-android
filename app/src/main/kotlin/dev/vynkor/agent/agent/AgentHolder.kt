package dev.vynkor.agent.agent

import dev.vynkor.agent.Agent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide handle on the live Agent (so services like NotificationListener
 * reach the push paths) plus a thread-safe connection-state flow the UI
 * observes for its live indicator.
 */
object AgentHolder {
    @Volatile
    var agent: Agent? = null

    val connectionState = MutableStateFlow(false)
}
