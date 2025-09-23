package org.syndes.terminal

import android.content.Context

/**
 * Module API
 */

interface ScriptRuntime {
    fun executeActionsNow(actions: List<String>)
    fun scheduleActionsDelay(scriptId: String, delayMillis: Long, actions: List<String>): String
}

sealed class ModuleResult {
    data class Executed(val info: String = "", val actions: List<String> = emptyList()) : ModuleResult()
    data class Scheduled(val info: String = "", val delayMillis: Long = 0L, val actions: List<String> = emptyList()) : ModuleResult()
    data class Error(val message: String) : ModuleResult()
}

/** Optional */
interface Module {
    val name: String
    fun matchesCondition(line: String): Boolean
    fun handleCondition(ctx: Context, conditionLine: String, actions: List<String>, runtime: ScriptRuntime): ModuleResult
}
