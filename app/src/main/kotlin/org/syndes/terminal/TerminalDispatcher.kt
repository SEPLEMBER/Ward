package org.syndes.terminal

import android.content.Context
import android.util.Log

/**
 * TerminalDispatcher — перебирает доступные реализации Terminal* и вызывает у них execute(...)
 *
 * Поведение:
 *  - сначала пытаем Terminal, потом Terminal2, потом Terminal3 (если они есть в класспате);
 *  - для каждого backend'а пытаем вызвать:
 *      1) execute(String, Context)
 *      2) execute(String)
 *      3) любой execute(...) у которого первый параметр String
 *  - если вызов выполнился БЕЗ исключения, считаем команду обработанной и сразу возвращаем
 *    строковый результат (если метод вернул null/Unit/пустую строку — вернём пустую строку),
 *    чтобы не запускать лишние реализации и не печатать "Error: command not found"
 *    в тех случаях, когда реализация выполняет только побочный эффект (например, открывает Activity).
 *  - если вызов бросил исключение — логируем и пробуем следующую реализацию.
 *
 * Возвращает непустую строку, или пустую строку "" если backend ничего не вернул, или
 * "Error: command not found" если ни одна реализация не смогла обработать команду.
 */
class TerminalDispatcher {

    private val instances = mutableListOf<Any>()

    init {
        addIfPresent("org.syndes.terminal.Terminal")
        addIfPresent("org.syndes.terminal.Terminal2")
        addIfPresent("org.syndes.terminal.Terminal3")
    }

    private fun addIfPresent(className: String) {
        try {
            val cls = Class.forName(className)
            val ctor = try { cls.getDeclaredConstructor() } catch (_: NoSuchMethodException) { null }
            val inst = when {
                ctor != null -> {
                    ctor.isAccessible = true
                    ctor.newInstance()
                }
                else -> {
                    // возможно Kotlin object singleton — попробуем получить INSTANCE
                    try { cls.getField("INSTANCE").get(null) } catch (_: Throwable) { null }
                }
            }
            if (inst != null) instances.add(inst)
        } catch (_: Throwable) {
            // Класс отсутствует или не подгрузился — просто пропускаем (ожидаемое поведение)
        }
    }

    /**
     * Выполнить команду через первый работающий backend.
     * Возвращает:
     *  - результат в виде строки (res.toString()) если backend вернул что-то, кроме Unit/null;
     *  - пустую строку "" если backend вернул null или Unit (т.е. обработал команду без текстового вывода);
     *  - "Error: command not found" если ни один backend не обработал команду (все бросали исключение или не найдены).
     */
    fun execute(command: String, ctx: Context): String {
        for (inst in instances) {
            try {
                // 1) execute(String, Context) — ищем метод, в котором первый параметр String и всего 2 параметра
                val m2 = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" && m.parameterCount == 2 && m.parameterTypes[0] == String::class.java
                }
                if (m2 != null) {
                    try {
                        val resAny = m2.invoke(inst, command, ctx)
                        return normalizeResult(resAny)
                    } catch (e: Throwable) {
                        // backend упал — логируем и пробуем следующий
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

                // 3) любой execute(...), первый параметр String (если есть)
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
                // редкий случай — проблемы с рефлексией у самого класса; пробуем следующий backend
                Log.d("TerminalDispatcher", "reflection error for ${inst.javaClass.simpleName}: ${e.message}")
            }
        }

        return "Error: command not found"
    }

    // Нормализуем результат вызова: null/Unit -> "" ; иначе -> res.toString()
    private fun normalizeResult(resAny: Any?): String {
        if (resAny == null) return ""
        // kotlin.Unit detection
        if (resAny is kotlin.Unit) return ""
        // Если метод вернул строку — вернуть её; иначе использовать toString().
        return try {
            resAny.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    /** Для отладки — список доступных backends (simple names) */
    fun availableBackends(): List<String> = instances.map { it.javaClass.simpleName }
}
