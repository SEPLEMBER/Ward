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

object ScriptHandler {

    private val handler = Handler(Looper.getMainLooper())
    private val activeSchedules = ConcurrentHashMap<String, MutableList<RunnableHolder>>()
    private val idCounter = AtomicInteger(1)

    private data class RunnableHolder(val id: String, val runnable: Runnable)

    fun startScriptFromUri(ctx: Context, scriptUri: Uri): String {
        val doc = DocumentFile.fromSingleUri(ctx, scriptUri) ?: return "Error: cannot access script Uri"
        val content = readTextFromDocument(ctx, doc) ?: return "Error: cannot read script"
        return startScriptFromText(ctx, content)
    }

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
     * Validate script text (parse header & body, check module availability and whether conditions are recognized)
     * Returns textual report but does not start scheduling or execute actions.
     */
    fun validateScriptText(ctx: Context, scriptText: String): String {
        val lines = scriptText.lines().map { it.trimEnd() }
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
                    val parts = content.split("\\s+".toRegex(), limit = 2)
                    if (parts.size == 2) header[parts[0].lowercase()] = parts[1].trim()
                }
                continue
            } else {
                bodyStart = i
                break
            }
        }

        val modulesSpec = header["metadata"] ?: header["modules"] ?: ""
        if (modulesSpec.isBlank()) return "Error: no #metadata or #modules specified"

        val moduleNames = modulesSpec.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // check modules exist (MVP: only Code1Module exists)
        for (m in moduleNames) {
            if (!m.equals("Code1", ignoreCase = true)) return "Error: module '$m' not found (MVP supports Code1)"
        }

        val bodyLines = lines.drop(bodyStart).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        val unrecognized = mutableListOf<String>()
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
                if (i < bodyLines.size && bodyLines[i].equals("fi", ignoreCase = true)) i++
                // validate condition with Code1Module
                val match = Code1Module.matchCondition(cond)
                if (match == null) unrecognized.add(cond)
                // optional: validate action lines format? skip for now
            } else {
                // immediate action or conditionless line
                val condline = if (line.startsWith("- ")) line.removePrefix("- ").trim() else line
                val match = Code1Module.matchCondition(condline)
                if (match == null) {
                    // treat as action (command) — we consider actions valid (we cannot check all commands here)
                }
                i++
            }
        }

        return if (unrecognized.isEmpty()) "OK: script syntax looks valid (modules found and conditions recognized)"
        else "Validation: found ${unrecognized.size} unrecognized condition(s):\n" + unrecognized.joinToString("\n")
    }

    fun startScriptFromText(ctx: Context, scriptText: String): String {
        val lines = scriptText.lines().map { it.trimEnd() }

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
                    val parts = content.split("\\s+".toRegex(), limit = 2)
                    if (parts.size == 2) header[parts[0].lowercase()] = parts[1].trim()
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

        val modules = mutableListOf<Any>()
        for (m in moduleNames) {
            if (m.equals("Code1", ignoreCase = true) || m.equals("code1", ignoreCase = true)) modules.add(Code1Module)
            else return "Error: module '$m' not found"
        }

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
                if (i < bodyLines.size && bodyLines[i].equals("fi", ignoreCase = true)) i++
                parsedBlocks.add(ParsedBlock(cond, actions))
            } else {
                if (line.startsWith("- ")) parsedBlocks.add(ParsedBlock(line.removePrefix("- ").trim(), emptyList()))
                else parsedBlocks.add(ParsedBlock(line, emptyList()))
                i++
            }
        }

        val scriptId = "script-${idCounter.getAndIncrement()}"
        activeSchedules[scriptId] = mutableListOf()

        val runtime = object : ScriptRuntime {
            override fun executeActionsNow(actions: List<String>) {
                Thread {
                    val term = Terminal()
                    for (act in actions) {
                        try { term.execute(act, ctx) } catch (_: Throwable) {}
                    }
                }.start()
            }

            override fun scheduleActionsDelay(scriptIdLocal: String, delayMillis: Long, actions: List<String>): String {
                val rid = "${scriptIdLocal}-${System.currentTimeMillis()}"
                val runnable = Runnable {
                    executeActionsNow(actions)
                    activeSchedules[scriptId]?.removeIf { it.id == rid }
                }
                handler.postDelayed(runnable, delayMillis)
                activeSchedules[scriptId]?.add(RunnableHolder(rid, runnable))
                return rid
            }
        }

        val log = StringBuilder()
        for (block in parsedBlocks) {
            var handled = false
            for (mod in modules) {
                if (mod is Code1Module) {
                    val match = mod.matchCondition(block.conditionLine)
                    if (match != null) {
                        val res = mod.handleMatched(ctx, match, block.actions, runtime)
                        when (res) {
                            is ModuleResult.Executed -> {
                                runtime.executeActionsNow(res.actions.ifEmpty { block.actions })
                                log.append("Executed: ${res.info}\n")
                            }
                            is ModuleResult.Scheduled -> {
                                // schedule via handler
                                val rid = "${scriptId}-${System.currentTimeMillis()}"
                                val runnable = Runnable {
                                    runtime.executeActionsNow(res.actions.ifEmpty { block.actions })
                                    activeSchedules[scriptId]?.removeIf { it.id == rid }
                                }
                                handler.postDelayed(runnable, res.delayMillis)
                                activeSchedules[scriptId]?.add(RunnableHolder(rid, runnable))
                                log.append("Scheduled: ${res.info}\n")
                            }
                            is ModuleResult.Error -> {
                                log.append("Module error: ${res.message}\n")
                            }
                        }
                        handled = true
                        break
                    }
                }
            }
            if (!handled) {
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

    fun stopScript(scriptId: String): String {
        val list = activeSchedules.remove(scriptId) ?: return "No such active script: $scriptId"
        for (holder in list) handler.removeCallbacks(holder.runnable)
        return "Stopped script $scriptId and cancelled ${list.size} scheduled tasks"
    }
}
