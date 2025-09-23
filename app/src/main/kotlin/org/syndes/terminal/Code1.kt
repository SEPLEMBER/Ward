package org.syndes.terminal

import android.content.Context
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

/**
 * Simple module implementation (Code1).
 * - распознаёт "if time HH:MM" (назначает выполнение actions в ближайший HH:MM)
 * - распознаёт "wait:N sec" (внутри actions может быть wait, но мы также поддерживаем отдельную команды)
 *
 * Методы:
 *  - matchCondition(line): Match?       // возвращает Match (структура), если подходит условие
 *  - handleMatched(ctx, match, actions, runtime): ModuleResult  // выполняет/планирует
 *
 * ВАЖНО: модуль НЕ пишет в логи и не использует Log.*
 */

object Code1Module {

    // patterns we support
    private val TIME_PATTERN = Pattern.compile("""^if\s+time\s+(\d{1,2}:\d{2})\s*$""", Pattern.CASE_INSENSITIVE)
    private val WAIT_PATTERN = Pattern.compile("""^wait\s*:\s*(\d+)\s*sec\s*$""", Pattern.CASE_INSENSITIVE)

    /** Результат сопоставления */
    data class Match(val type: String, val groups: List<String>)

    fun matchCondition(line: String): Match? {
        val tmat = TIME_PATTERN.matcher(line)
        if (tmat.matches()) {
            return Match("time", listOf(tmat.group(1)))
        }
        val wmat = WAIT_PATTERN.matcher(line)
        if (wmat.matches()) {
            return Match("wait", listOf(wmat.group(1)))
        }
        return null
    }

    fun handleMatched(
        ctx: Context,
        match: Match,
        actions: List<String>,
        runtime: ScriptRuntime
    ): ModuleResult {
        return try {
            when (match.type) {
                "time" -> {
                    val hhmm = match.groups.getOrNull(0) ?: return ModuleResult.Error("bad time")
                    val parts = hhmm.split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return ModuleResult.Error("bad hour")
                    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return ModuleResult.Error("bad minute")

                    val now = Calendar.getInstance()
                    val target = Calendar.getInstance()
                    target.set(Calendar.HOUR_OF_DAY, max(0, minOf(hour, 23)))
                    target.set(Calendar.MINUTE, max(0, minOf(minute, 59)))
                    target.set(Calendar.SECOND, 0)
                    target.set(Calendar.MILLISECOND, 0)
                    if (target.timeInMillis <= now.timeInMillis) {
                        target.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    val delay = target.timeInMillis - now.timeInMillis
                    val id = runtime.scheduleActionsDelay("timeTask-${hour}-${minute}-${System.currentTimeMillis()}", delay, actions)
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    ModuleResult.Scheduled("scheduled at ${sdf.format(target.time)} (delay ${delay} ms), id=$id")
                }

                "wait" -> {
                    val secs = match.groups.getOrNull(0)?.toLongOrNull() ?: return ModuleResult.Error("bad wait")
                    val delay = secs * 1000L
                    val id = runtime.scheduleActionsDelay("waitTask-${System.currentTimeMillis()}", delay, actions)
                    ModuleResult.Scheduled("scheduled after ${secs}s, id=$id")
                }

                else -> ModuleResult.Error("unsupported match type")
            }
        } catch (t: Throwable) {
            ModuleResult.Error("module error")
        }
    }
}
