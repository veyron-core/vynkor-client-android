package dev.vynkor.agent.agent

import dev.vynkor.agent.Agent

/** Process-wide handle on the live Agent so services (NotificationListener)
 * can reach the push paths. */
object AgentHolder {
    @Volatile
    var agent: Agent? = null
}
