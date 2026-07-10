package com.jetbrains.glmproxy.client

import org.slf4j.Logger

/**
 * Shared body-logging helper for upstream clients. Emits one line per proxied body:
 *   ">>> upstream <json>"  when sending to the upstream,
 *   "<<< downstream <json>" when receiving from the upstream.
 * No-op unless `enabled` (debug.logBodies) is true.
 */
object ProxyLogger {
    fun log(logger: Logger, marker: String, direction: String, body: String, enabled: Boolean) {
        if (!enabled) return
        logger.info("{} {} {}", marker, direction, body)
    }
}
