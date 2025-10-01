package org.syndes.terminal

import android.content.Context

/**
 * Module API for script engine
 */

sealed class ModuleResult {
    data class Executed(val info: String = "", val actions: List<String> = emptyList()) : ModuleResult()
    data class Scheduled(val info: String = "", val delayMillis: Long = 0L, val actions: List<String> = emptyList()) : ModuleResult()
    data class Error(val message: String) : ModuleResult()
}

/** Runtime callbacks provided to modules so they can request execution/scheduling */
interface ScriptRuntime {
    /**
     * Execute actions immediately (may be performed on worker thread)
     * Actions are terminal commands (strings) that will be passed to Terminal.execute(...)
     */
    fun executeActionsNow(actions: List<String>)

    /**
     * Schedule actions after delayMillis.
     * Returns an opaque id for the scheduled job (useful for cancellation tracking).
     */
    fun scheduleActionsDelay(scriptId: String, delayMillis: Long, actions: List<String>): String
}

/** Optional module interface (not required for MVP if using built-in modules) */
interface Module {
    val name: String
    /** If module recognizes condition line returns a match-like object (or null if not recognized) */
    fun matchesCondition(line: String): Any?
    /**
     * Handle a matched condition.
     * Should return ModuleResult: Executed, Scheduled or Error.
     * Do not execute terminal actions directly — use runtime to schedule/execute.
     */
    fun handleCondition(ctx: Context, match: Any, actions: List<String>, runtime: ScriptRuntime): ModuleResult
}
