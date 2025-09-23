package org.syndes.terminal

import android.content.Context

/**
 * Общие API для модульной системы скриптов
 *
 * Помещается в ModuleApi.kt
 *
 * - ScriptRuntime — интерфейс, который ScriptActivity реализует и передаёт модулям;
 * - ModuleResult — sealed класс результатов от модуля;
 * - Module — (опционально) интерфейс модуля — можно использовать, если захотим регистрировать модули централизованно.
 *
 * Файл НЕ использует Log.* и не пишет никаких внешних логов/телеметрии.
 */

/** Runtime, через который модуль может выполнить или запланировать действия (action-строки) */
interface ScriptRuntime {
    /**
     * Выполнить список action-строк немедленно (в фоне).
     * Модули вызывают этот метод для немедленного исполнения действий.
     */
    fun executeActionsNow(actions: List<String>)

    /**
     * Запланировать выполнение actions через delayMillis миллисекунд.
     * Возвращает внутренний id запланированной задачи (строку).
     */
    fun scheduleActionsDelay(scriptId: String, delayMillis: Long, actions: List<String>): String
}

/** Результат, который возвращает модуль при обработке условия */
sealed class ModuleResult {
    data class Executed(val info: String = "") : ModuleResult()
    data class Scheduled(val info: String = "") : ModuleResult()
    data class Error(val message: String) : ModuleResult()
}

/**
 * (Опциональный) интерфейс модуля.
 * Если используешь динамическую регистрацию модулей, реализуй этот интерфейс.
 */
interface Module {
    /** Идентификатор модуля (например "Code1") */
    val name: String

    /**
     * Попробовать сопоставить условие (строку) — вернуть true, если модуль понимает эту строку.
     * Модуль может затем вызвать соответствующий обработчик.
     */
    fun matchesCondition(line: String): Boolean

    /**
     * Обработать условие — модуль реализует логику планирования/исполнения.
     * Возвращает ModuleResult.
     */
    fun handleCondition(ctx: Context, conditionLine: String, actions: List<String>, runtime: ScriptRuntime): ModuleResult
}
