package org.syndes.terminal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScriptActivity — минимальный обработчик скриптов.
 * Основные публичные методы:
 *  - startScriptFromUri(ctx, uri) : String  // стартует парсинг/планирование и возвращает статус
 *  - stopScript(scriptId) : String          // останавливает запланированные задачи
 *
 * Скрипт format:
 *  #metadata: Code1,Code2
 *  #name: myscript
 *
 *  if time 12:00
 *  - wait:10 sec
 *  - open YouTube
 *  fi
 *
 *  или просто:
 *  - open YouTube
 */
class ScriptActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val activeSchedules = ConcurrentHashMap<String, MutableList<RunnableHolder>>()
    private val idCounter = AtomicInteger(1)

    private data class RunnableHolder(val id: String, val runnable: Runnable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Это Activity может не иметь UI — используется как helper. Если нужен UI, можно добавить.
    }

    /** Читает скрипт из DocumentFile (SAF Uri) и запускает */
    fun startScriptFromUri(ctx: Context, scriptUri: Uri): String {
        val doc = DocumentFile.fromSingleUri(ctx, scriptUri) ?: return "Error: cannot access script"
        val text = try {
            ctx.contentResolver.openInputStream(doc.uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: return "Error: cannot read script"
        } catch (t: Throwable) {
            return "Error: failed to read script"
        }
        return startScriptFromText(ctx, text)
    }

    /** Парсит header, загружает модули по #metadata и обрабатывает тело */
    fun startScriptFromText(ctx: Context, scriptText: String): String {
        val lines = scriptText.lines().map { it.trimEnd() }

        // parse header
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

        // load modules (only those requested). For MVP - we have Code1Module in code1.kt
        val modules = mutableListOf<Any>() // keep as Any to avoid adding Module interface here
        for (m in moduleNames) {
            if (m.equals("Code1", ignoreCase = true) || m.equals("code1", ignoreCase = true)) {
                modules.add(Code1Module) // object defined in code1.kt
            } else {
                return "Error: module '$m' not found"
            }
        }

        // parse body (strip comments and empty)
        val bodyLines = lines.drop(bodyStart).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        // simple parsing: blocks "if ... fi" with action lines "- ..." inside; otherwise stand-alone "- ..." lines
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
                // skip fi if present
                if (i < bodyLines.size && bodyLines[i].equals("fi", ignoreCase = true)) i++
                parsedBlocks.add(ParsedBlock(cond, actions))
            } else {
                // immediate action or conditionless line
                if (line.startsWith("- ")) parsedBlocks.add(ParsedBlock(line.removePrefix("- ").trim(), emptyList()))
                else parsedBlocks.add(ParsedBlock(line, emptyList()))
                i++
            }
        }

        // create script id
        val scriptId = "script-${idCounter.getAndIncrement()}"
        activeSchedules[scriptId] = mutableListOf()

        // runtime object for modules to execute or schedule actions
        val runtime = object : ScriptRuntime {
            override fun executeActionsNow(actions: List<String>) {
                // execute in background thread to avoid blocking UI
                Thread {
                    val term = Terminal()
                    for (act in actions) {
                        try {
                            term.execute(act, ctx)
                        } catch (_: Throwable) { /* silently ignore per privacy rule */ }
                    }
                }.start()
            }

            override fun scheduleActionsDelay(scriptLocalId: String, delayMillis: Long, actions: List<String>): String {
                val rid = "${scriptLocalId}-${System.currentTimeMillis()}"
                val runnable = Runnable {
                    executeActionsNow(actions)
                    // remove from active list
                    activeSchedules[scriptId]?.removeIf { it.id == rid }
                }
                handler.postDelayed(runnable, delayMillis)
                activeSchedules[scriptId]?.add(RunnableHolder(rid, runnable))
                return rid
            }
        }

        // process blocks: try modules in order. For each module, we ask it to match the condition
        val resultLog = StringBuilder()
        for (block in parsedBlocks) {
            var handled = false
            for (mod in modules) {
                if (mod is Code1Module) {
                    val match = mod.matchCondition(block.conditionLine)
                    if (match != null) {
                        val res = mod.handleMatched(ctx, match, block.actions, runtime)
                        when (res) {
                            is ModuleResult.Executed -> resultLog.append("Executed: ${res.info}\n")
                            is ModuleResult.Scheduled -> resultLog.append("Scheduled: ${res.info}\n")
                            is ModuleResult.Error -> resultLog.append("Module error: ${res.message}\n")
                        }
                        handled = true
                        break
                    }
                }
            }
            if (!handled) {
                // if no module handled and it's immediate action
                if (block.actions.isEmpty()) {
                    runtime.executeActionsNow(listOf(block.conditionLine))
                    resultLog.append("Executed immediate: ${block.conditionLine}\n")
                } else {
                    resultLog.append("Unrecognized condition: ${block.conditionLine}\n")
                }
            }
        }

        return "Script started: $scriptId\n" + resultLog.toString()
    }

    /** Остановить скрипт и отменить все запланированные runnables */
    fun stopScript(scriptId: String): String {
        val list = activeSchedules.remove(scriptId) ?: return "No such active script: $scriptId"
        for (h in list) {
            handler.removeCallbacks(h.runnable)
        }
        return "Stopped $scriptId (cancelled ${list.size} tasks)"
    }

    // internal
    private data class ParsedBlock(val conditionLine: String, val actions: List<String>)
}
