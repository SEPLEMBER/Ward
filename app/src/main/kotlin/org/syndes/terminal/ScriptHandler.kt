package org.syndes.terminal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScriptHandler — диспетчер скриптов (.syd / .cyd / .sydscrypt).
 *
 * API:
 *  - startScriptFromUri(ctx, uri): String   // старт и краткий статус (возвращаемое значение выводится в терминал)
 *  - startScriptFromText(ctx, text): String // старт по тексту (полезно для тестов / edit->run)
 *  - stopScript(scriptId): String           // остановка (отмена запланированных задач)
 *
 * Реализация минимальная и безопасная:
 * - планирование -- in-memory (Handler.postDelayed). Надёжность ограничена временем жизни процесса.
 * - модули: загружаются строго по названию в #metadata / #modules. В текущем MVP поддержан только Code1Module.
 * - выполнения действий делаются через Terminal.execute(action, ctx) в отдельном потоке.
 *
 * Приватность: никакой аналитики/логов.
 */

object ScriptHandler {

    private val handler = Handler(Looper.getMainLooper())
    private val activeSchedules = ConcurrentHashMap<String, MutableList<RunnableHolder>>()
    private val idCounter = AtomicInteger(1)

    private data class RunnableHolder(val id: String, val runnable: Runnable)

    /**
     * Запустить скрипт по Uri (SAF DocumentFile single uri).
     * Возвращает строку со статусом (например: "Script started: script-1\nScheduled: ...")
     */
    fun startScriptFromUri(ctx: Context, scriptUri: Uri): String {
        val doc = DocumentFile.fromSingleUri(ctx, scriptUri) ?: return "Error: cannot access script Uri"
        val content = readTextFromDocument(ctx, doc) ?: return "Error: cannot read script"
        return startScriptFromText(ctx, content)
    }

    /** Прочитать текст из DocumentFile (SAF) */
    private fun readTextFromDocument(ctx: Context, doc: DocumentFile): String? {
        return try {
            ctx.contentResolver.openInputStream(doc.uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Запустить скрипт по тексту.
     * Формат header: строки начинающиеся с '#' — ищем ключи 'metadata' или 'modules'.
     * Тело: поддерживаем блоки if ... fi (внутри - линии, начинающиеся с "- ") и одиночные action-линии.
     */
    fun startScriptFromText(ctx: Context, scriptText: String): String {
        val lines = scriptText.lines().map { it.trimEnd() }

        // --- парсим header ---
        val header = mutableMapOf<String, String>()
        var bodyStart = 0
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val content = line.removePrefix("#").trim()
                val idx = content.indexOf(":")
                if (idx >= 0) {
                    val key = content.substring(0, idx).trim().lowercase()
                    val value = content.substring(idx + 1).trim()
                    header[key] = value
                } else {
                    // поддерживаем вариант "#metadata Code1,Code2"
                    val parts = content.split("\\s+".toRegex(), limit = 2)
                    if (parts.size == 2) {
                        header[parts[0].lowercase()] = parts[1].trim()
                    }
                }
                continue
            } else {
                bodyStart = i
                break
            }
        }

        val modulesSpec = header["metadata"] ?: header["modules"] ?: ""
        if (modulesSpec.isBlank()) {
            return "Error: no #metadata or #modules specified"
        }
        val moduleNames = modulesSpec.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // --- load modules by name (MVP: only Code1Module supported) ---
        val modules = mutableListOf<Any>()
        for (m in moduleNames) {
            if (m.equals("Code1", ignoreCase = true) || m.equals("code1", ignoreCase = true)) {
                modules.add(Code1Module)
            } else {
                return "Error: module '$m' not found"
            }
        }

        // --- parse body (skip comments) ---
        val bodyLines = lines.drop(bodyStart).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        data class ParsedBlock(val conditionLine: String, val actions: List<String>)

        val parsedBlocks = mutableListOf<ParsedBlock>()
        var i = 0
        while (i < bodyLines.size) {
            val line = bodyLines[i]
            if (line.startsWith("if ", ignoreCase = true)) {
                val cond = line
                val actions = mutableListOf<String>()
                i++
                while (i < bodyLines.size && !bodyLines[i].equals("fi", ignoreCase = true)) {
                    val l = bodyLines[i]
                    if (l.startsWith("- ")) actions.add(l.removePrefix("- ").trim()) else actions.add(l)
                    i++
                }
                if (i < bodyLines.size && bodyLines[i].equals("fi", ignoreCase = true)) i++ // skip fi
                parsedBlocks.add(ParsedBlock(cond, actions))
            } else {
                // immediate action or conditionless line
                if (line.startsWith("- ")) parsedBlocks.add(ParsedBlock(line.removePrefix("- ").trim(), emptyList()))
                else parsedBlocks.add(ParsedBlock(line, emptyList()))
                i++
            }
        }

        // --- prepare runtime for modules ---
        val scriptId = "script-${idCounter.getAndIncrement()}"
        activeSchedules[scriptId] = mutableListOf()

        val runtime = object : ScriptRuntime {
            override fun executeActionsNow(actions: List<String>) {
                Thread {
                    val term = Terminal()
                    for (act in actions) {
                        try {
                            term.execute(act, ctx)
                        } catch (_: Throwable) {
                            // per privacy/no-logs rule: swallow errors silently
                        }
                    }
                }.start()
            }

            override fun scheduleActionsDelay(scriptLocalId: String, delayMillis: Long, actions: List<String>): String {
                val rid = "${scriptLocalId}-${System.currentTimeMillis()}"
                val runnable = Runnable {
                    executeActionsNow(actions)
                    activeSchedules[scriptId]?.removeIf { it.id == rid }
                }
                handler.postDelayed(runnable, delayMillis)
                activeSchedules[scriptId]?.add(RunnableHolder(rid, runnable))
                return rid
            }
        }

        // --- process parsed blocks ---
        val log = StringBuilder()
        for (block in parsedBlocks) {
            var handled = false
            for (mod in modules) {
                if (mod is Code1Module) {
                    val match = mod.matchCondition(block.conditionLine)
                    if (match != null) {
                        val res = mod.handleMatched(ctx, match, block.actions, runtime)
                        when (res) {
                            is ModuleResult.Executed -> log.append("Executed: ${res.info}\n")
                            is ModuleResult.Scheduled -> log.append("Scheduled: ${res.info}\n")
                            is ModuleResult.Error -> log.append("Module error: ${res.message}\n")
                        }
                        handled = true
                        break
                    }
                }
            }
            if (!handled) {
                // if no module handled and it's an immediate action - run now
                if (block.actions.isEmpty()) {
                    runtime.executeActionsNow(listOf(block.conditionLine))
                    log.append("Executed immediate: ${block.conditionLine}\n")
                } else {
                    log.append("Unrecognized condition: ${block.conditionLine}\n")
                }
            }
        }

        return "Script started: $scriptId\n" + log.toString()
    }

    /**
     * Остановить скрипт и отменить все запланированные runnables.
     */
    fun stopScript(scriptId: String): String {
        val list = activeSchedules.remove(scriptId) ?: return "No such active script: $scriptId"
        for (holder in list) {
            handler.removeCallbacks(holder.runnable)
        }
        return "Stopped script $scriptId and cancelled ${list.size} scheduled tasks"
    }
}
