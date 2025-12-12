package org.syndes.terminal

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Надёжный TerminalDispatcher:
 *  - ищет реализации Terminal, Terminal2, Terminal3;
 *  - гибко вызывает execute(...) независимо от порядка аргументов (String/Context);
 *  - считает backend обработавшим команду, если вызов завершился БЕЗ исключения
 *    (даже если результат null/Unit -> вернёт "").
 */
class TerminalDispatcher {

    private val instances = mutableListOf<Any>()

    init {
        // Пытаемся через reflection
        addIfPresent("org.syndes.terminal.Terminal")
        addIfPresent("org.syndes.terminal.Terminal2")
        addIfPresent("org.syndes.terminal.Terminal3")

        // Защита: если по какой-то причине reflection ничего не нашёл,
        // попробуем напрямую создать известные классы (если они доступны в проекте).
        if (instances.isEmpty()) {
            tryDirectConstructFallback()
        }
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
        } catch (e: Throwable) {
            // Класс не найден/не подгрузился — пропускаем, это ожидаемо
            Log.d("TerminalDispatcher", "addIfPresent skipped $className: ${e.message}")
        }
    }

    // Fallback: пытаемся напрямую создать экземпляры через конструкторы (если классы компилируются в проекте)
    private fun tryDirectConstructFallback() {
        try {
            // (в try/catch: если класса нет — компилятор всё равно скомпилирует этот код,
            // потому что класс действительно присутствует в проекте; но на всякий случай — ловим)
            try { instances.add(Terminal()) } catch (_: Throwable) {}
            try { instances.add(Terminal2()) } catch (_: Throwable) {}
            try { instances.add(Terminal3()) } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // игнорируем — это чисто защитный код
        }
    }

    /**
     * Основная логика выполнения.
     * Перебираем все найденные экземпляры и пытаемся вызвать у них execute(...).
     * Если вызов выполнен без исключения — считаем команду обработанной и возвращаем
     * строковое представление результата (или "" если null/Unit).
     */
    fun execute(command: String, ctx: Context): String {
        for (inst in instances) {
            val cls = inst.javaClass
            val methods = try {
                cls.methods.filter { it.name == "execute" }
            } catch (e: Throwable) {
                Log.d("TerminalDispatcher", "cannot list methods for ${cls.simpleName}: ${e.message}")
                continue
            }

            for (m in methods) {
                try {
                    val resAny = invokeExecuteMethodSafely(m, inst, command, ctx)
                    // если вызов завершился без исключения — считаем обработанным
                    if (resAny != INVOKE_FAILED) {
                        return normalizeResult(resAny)
                    }
                } catch (e: Throwable) {
                    // Защита: на уровне invokeExecuteMethodSafely уже ловим исключения,
                    // но на всякий случай логируем и пробуем следующий метод.
                    Log.d("TerminalDispatcher", "invoke failed for ${cls.simpleName}.${m.name}: ${e.message}")
                }
            }
        }

        return "Error: command not found"
    }

    // Спец. маркер: метод бросил исключение -> вернуть INVOKE_FAILED
    private val INVOKE_FAILED = Any()

    // Пытаемся корректно построить аргументы и вызвать метод m.
    // Возвращаем INVOKE_FAILED, если метод бросил исключение при вызове.
    private fun invokeExecuteMethodSafely(m: Method, inst: Any, command: String, ctx: Context): Any? {
        return try {
            val ptypes = m.parameterTypes
            when (ptypes.size) {
                0 -> {
                    // execute() без параметров — вызываем (маловероятно)
                    m.invoke(inst)
                }
                1 -> {
                    // Если единственный параметр - String -> вызвать (command)
                    if (ptypes[0] == String::class.java) {
                        m.invoke(inst, command)
                    } else if (Context::class.java.isAssignableFrom(ptypes[0])) {
                        // unlikely: execute(ctx)
                        m.invoke(inst, ctx)
                    } else {
                        // неподдерживаемый подпись — пропускаем
                        INVOKE_FAILED
                    }
                }
                2 -> {
                    // Популярные варианты:
                    // (String, Context) или (Context, String) или (String, Any)
                    val first = ptypes[0]
                    val second = ptypes[1]

                    // оба ожидаемых типа присутствуют
                    if (first == String::class.java && Context::class.java.isAssignableFrom(second)) {
                        m.invoke(inst, command, ctx)
                    } else if (Context::class.java.isAssignableFrom(first) && second == String::class.java) {
                        m.invoke(inst, ctx, command)
                    } else if (first == String::class.java) {
                        // второй параметр другой — попробуем подставить ctx если совместим, иначе null
                        val arg1: Any = command
                        val arg2: Any? = if (Context::class.java.isAssignableFrom(second)) ctx else null
                        m.invoke(inst, arg1, arg2)
                    } else {
                        // попытка: подставить command и ctx в первые два аргумента (попробуем)
                        val arg0: Any? = if (first == String::class.java) command else if (Context::class.java.isAssignableFrom(first)) ctx else null
                        val arg1: Any? = if (second == String::class.java) command else if (Context::class.java.isAssignableFrom(second)) ctx else null
                        m.invoke(inst, arg0, arg1)
                    }
                }
                else -> {
                    // больше двух параметров: постараемся заполнить позиции, где ожидается String и Context,
                    // остальные аргументы передадим как null
                    val args = Array<Any?>(ptypes.size) { null }
                    for (i in ptypes.indices) {
                        val t = ptypes[i]
                        if (args[i] == null && t == String::class.java) args[i] = command
                        else if (args[i] == null && Context::class.java.isAssignableFrom(t)) args[i] = ctx
                    }
                    m.invoke(inst, *args)
                }
            }
        } catch (e: Throwable) {
            // Логируем и возвращаем маркер ошибки, чтобы вызвать следующий метод/бэкенд
            Log.d("TerminalDispatcher", "method ${m.name} on ${inst.javaClass.simpleName} threw: ${e.message}")
            INVOKE_FAILED
        }
    }

    // Null/Unit -> "" ; иначе res.toString()
    private fun normalizeResult(resAny: Any?): String {
        if (resAny == null) return ""
        if (resAny === INVOKE_FAILED) return "" // safety (вызов считался неуспешным, но всё же)
        if (resAny is kotlin.Unit) return ""
        return try {
            resAny.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    // Для отладки — список доступных backends
    fun availableBackends(): List<String> = instances.map { it.javaClass.simpleName }
}
