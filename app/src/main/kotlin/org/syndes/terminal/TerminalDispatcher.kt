package org.syndes.terminal

import android.content.Context
import android.util.Log

/**
 * Исправленный TerminalDispatcher.
 *
 * Поведение:
 *  - гарантированно добавляет основной Terminal() если он доступен в компиляции;
 *  - затем пробует добавить Terminal2 и Terminal3 через reflection (опционально);
 *  - вызывает execute(...) у backend'ов в порядке: Terminal, Terminal2, Terminal3;
 *  - если вызов backend'а отработал без исключения — считаем команду обработанной и возвращаем результат
 *    (если backend вернул null/Unit -> возвращается пустая строка "").
 */
class TerminalDispatcher {

    private val instances = mutableListOf<Any>()

    init {
        // Сначала попробуем добавить основной Terminal напрямую (гарантируем встроенные команды)
        try {
            val t = Terminal()
            addInstanceIfNotDuplicate(t)
        } catch (_: Throwable) {
        }
            try {
        addInstanceIfNotDuplicate(Terminal2())
    } catch (_: Throwable) {}
            // Если по какой-то причине основного Terminal нет/конструктор бросил — продолжим попытки рефлексией
        }

        // Добавляем опциональные реализации через reflection (если они есть)
        addIfPresent("org.syndes.terminal.Terminal5")
        addIfPresent("org.syndes.terminal.Terminal3")
    }

    private fun addInstanceIfNotDuplicate(inst: Any) {
        val name = inst.javaClass.name
        if (instances.none { it.javaClass.name == name }) {
            instances.add(inst)
        }
    }

    private fun addIfPresent(className: String) {
        try {
            val cls = Class.forName(className)
            val ctor = try { cls.getDeclaredConstructor() } catch (_: NoSuchMethodException) { null }
            val inst = when {
                ctor != null -> {
                    ctor.isAccessible = true
                    try {
                        ctor.newInstance()
                    } catch (_: Throwable) {
                        null
                    }
                }
                else -> {
                    // возможно Kotlin object singleton — попробуем получить INSTANCE
                    try { cls.getField("INSTANCE").get(null) } catch (_: Throwable) { null }
                }
            }
            if (inst != null) addInstanceIfNotDuplicate(inst)
        } catch (_: Throwable) {
            // класс отсутствует или не подгрузился — пропускаем
        }
    }

    /**
     * Выполнить команду через первый работающий backend.
     * Если backend отработал без исключения — считаем команду обработанной.
     * Возвращаем строку результата или "" если backend ничего не вернул; если ни один backend не обработал —
     * возвращаем "Error: command not found".
     */
    fun execute(command: String, ctx: Context): String {
        for (inst in instances) {
            try {
                // 1) execute(String, Context)
                val m2 = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" && m.parameterCount == 2 && m.parameterTypes[0] == String::class.java
                }
                if (m2 != null) {
                    try {
                        val resAny = m2.invoke(inst, command, ctx)
                        return normalizeResult(resAny)
                    } catch (e: Throwable) {
                        // backend упал — пробуем следующий
                        Log.d("TerminalDispatcher", "backend ${inst.javaClass.simpleName} failed (2-arg): ${e.message}")
                        continue
                    }
                }

                // 2) execute(String)
                val m1 = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java
                }
                if (m1 != null) {
                    try {
                        val resAny = m1.invoke(inst, command)
                        return normalizeResult(resAny)
                    } catch (e: Throwable) {
                        Log.d("TerminalDispatcher", "backend ${inst.javaClass.simpleName} failed (1-arg): ${e.message}")
                        continue
                    }
                }

                // 3) любой execute(...), первый параметр String
                val mAny = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" && m.parameterCount >= 1 && m.parameterTypes[0] == String::class.java
                }
                if (mAny != null) {
                    try {
                        val resAny = if (mAny.parameterCount == 2) {
                            mAny.invoke(inst, command, ctx)
                        } else {
                            mAny.invoke(inst, command)
                        }
                        return normalizeResult(resAny)
                    } catch (e: Throwable) {
                        Log.d("TerminalDispatcher", "backend ${inst.javaClass.simpleName} failed (any-arg): ${e.message}")
                        continue
                    }
                }
            } catch (e: Throwable) {
                Log.d("TerminalDispatcher", "reflection error for ${inst.javaClass.simpleName}: ${e.message}")
            }
        }

        return "Error: command not found"
    }

    private fun normalizeResult(resAny: Any?): String {
        if (resAny == null) return ""
        if (resAny is kotlin.Unit) return ""
        return try {
            resAny.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    /** Для отладки — список доступных backends (simple names) */
    fun availableBackends(): List<String> = instances.map { it.javaClass.simpleName }
}
