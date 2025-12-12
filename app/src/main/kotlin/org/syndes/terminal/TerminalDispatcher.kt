package org.syndes.terminal

import android.content.Context
import java.lang.reflect.Method

/**
 * Диспетчер терминалов.
 * В рантайме пытается инициализировать Terminal, Terminal2, Terminal3 (в этом порядке).
 * Для вызова использует reflection и поддерживает сигнатуры:
 *   - execute(command: String, ctx: Context)
 *   - execute(command: String)
 *
 * Возвращает первый непустой/ненулевой результат; если никто не обработал — возвращает "Error: command not found".
 */
class TerminalDispatcher {

    private val instances = mutableListOf<Any>()

    init {
        // Попробуем подключить известные реализации (пакет тот же, что и activity/оригинальный Terminal)
        addIfPresent("org.syndes.terminal.Terminal")
        addIfPresent("org.syndes.terminal.Terminal2")
        addIfPresent("org.syndes.terminal.Terminal3")
    }

    private fun addIfPresent(className: String) {
        try {
            val cls = Class.forName(className)
            val ctor = try {
                cls.getDeclaredConstructor()
            } catch (_: NoSuchMethodException) {
                null
            }
            val inst = if (ctor != null) {
                ctor.isAccessible = true
                ctor.newInstance()
            } else {
                // попробуем newInstance через deprecated API (на случай kotlin object / singletons)
                try {
                    cls.getField("INSTANCE").get(null)
                } catch (_: Throwable) {
                    null
                }
            }
            if (inst != null) instances.add(inst)
        } catch (_: Throwable) {
            // класс отсутствует/не загрузился — пропускаем
        }
    }

    /**
     * Ведём поиск метода execute и вызываем его.
     * Пытаемся сначала сигнатуру (String, Context), потом (String).
     * Возвращаем первый ненулевой результат.
     */
    fun execute(command: String, ctx: Context): String {
        var lastEx: Throwable? = null
        for (inst in instances) {
            try {
                // 1) искать метод execute(String, Context/Activity/Any)
                val m2: Method? = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" &&
                    m.parameterCount == 2 &&
                    m.parameterTypes[0] == String::class.java
                }
                if (m2 != null) {
                    try {
                        val res = m2.invoke(inst, command, ctx) as? String
                        if (!res.isNullOrBlank()) return res
                        // если пустая строка — всё равно считаем, что обработано? Обычно нет — поэтому пропускаем и пробуем дальше
                    } catch (t: Throwable) {
                        lastEx = t
                        // пробуем следующую реализацию
                    }
                }

                // 2) попробовать execute(String)
                val m1: Method? = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == String::class.java
                }
                if (m1 != null) {
                    try {
                        val res = m1.invoke(inst, command) as? String
                        if (!res.isNullOrBlank()) return res
                    } catch (t: Throwable) {
                        lastEx = t
                    }
                }

                // 3) если ни 1 ни 2 не найдены, попробуем любой метод execute с первым параметром String
                val mAny: Method? = inst.javaClass.methods.firstOrNull { m ->
                    m.name == "execute" &&
                    m.parameterCount >= 1 &&
                    m.parameterTypes[0] == String::class.java
                }
                if (mAny != null) {
                    // попробуем вызвать, подставляя ctx если метод принимает 2 параметра, иначе только команду
                    try {
                        val args = if (mAny.parameterCount == 2) arrayOf(command, ctx) else arrayOf(command)
                        val res = mAny.invoke(inst, *args) as? String
                        if (!res.isNullOrBlank()) return res
                    } catch (t: Throwable) {
                        lastEx = t
                    }
                }
            } catch (t: Throwable) {
                lastEx = t
            }
        }

        // Ничего не обработано — возвращаем единый message, совместимый с остальной логикой.
        // Пользователь может изменить сюда поведение (например вернуть null) если предпочитает.
        return "Error: command not found"
    }
}
