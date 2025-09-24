package org.syndes.terminal

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

/**
 * Code1Module:
 * - if time HH:MM    -> schedule actions at next HH:MM
 * - wait:N sec       -> schedule after N seconds
 * - if exists <path> -> true if path exists in work dir (supports absolute home/... or relative)
 * - if size <|>|= N <path> -> compare size in bytes (supports suffix K/M)
 *
 * Module возвращает ModuleResult (НЕ выполняет действия напрямую).
 */
object Code1Module {

    private val TIME_PATTERN = Pattern.compile("""^if\s+time\s+(\d{1,2}:\d{2})\s*$""", Pattern.CASE_INSENSITIVE)
    private val WAIT_PATTERN = Pattern.compile("""^wait\s*:\s*(\d+)\s*sec\s*$""", Pattern.CASE_INSENSITIVE)
    private val EXISTS_PATTERN = Pattern.compile("""^if\s+exists\s+(.+)$""", Pattern.CASE_INSENSITIVE)
    private val SIZE_PATTERN = Pattern.compile("""^if\s+size\s*(>=|<=|>|<|=)\s*([\dKMkm]+)\s+(.+)$""", Pattern.CASE_INSENSITIVE)

    data class Match(val type: String, val groups: List<String>)

    fun matchCondition(line: String): Match? {
        val tmat = TIME_PATTERN.matcher(line)
        if (tmat.matches()) return Match("time", listOf(tmat.group(1)))
        val wmat = WAIT_PATTERN.matcher(line)
        if (wmat.matches()) return Match("wait", listOf(wmat.group(1)))
        val emat = EXISTS_PATTERN.matcher(line)
        if (emat.matches()) return Match("exists", listOf(emat.group(1)))
        val smat = SIZE_PATTERN.matcher(line)
        if (smat.matches()) return Match("size", listOf(smat.group(1), smat.group(2), smat.group(3)))
        return null
    }

    private fun parseBytesWithSuffix(s: String): Long {
        if (s.isEmpty()) return 0L
        val ss = s.trim()
        val last = ss.last()
        return try {
            when (last) {
                'K','k' -> (ss.dropLast(1).toLongOrNull() ?: 0L) * 1024L
                'M','m' -> (ss.dropLast(1).toLongOrNull() ?: 0L) * 1024L * 1024L
                else -> ss.toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) { 0L }
    }

    /** Resolve path inside work dir (SAF). */
    private fun resolveInWork(ctx: Context, path: String): DocumentFile? {
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("work_dir_uri", null) ?: return null
        val root = try { DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(uriStr)) } catch (_: Throwable) { null } ?: return null

        var components = path.split("/").filter { it.isNotEmpty() }.toMutableList()
        var curDir = root

        if (path.startsWith("/") || components.firstOrNull()?.equals("home", ignoreCase = true) == true) {
            curDir = root
            if (components.firstOrNull()?.equals("home", ignoreCase = true) == true) {
                components = components.drop(1).toMutableList()
            }
        } else {
            val curUri = prefs.getString("current_dir_uri", null)
            curUri?.let {
                val cur = try { DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(it)) } catch (_: Throwable) { null }
                if (cur != null) curDir = cur
            }
        }

        if (components.isEmpty()) return curDir

        for (comp in components) {
            when (comp) {
                "." -> {}
                ".." -> {
                    val parent = curDir.parentFile
                    if (parent != null && parent.uri != root.uri) curDir = parent
                }
                else -> {
                    val found = curDir.findFile(comp) ?: return null
                    curDir = found
                }
            }
        }
        return curDir
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
                    if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)

                    val delay = target.timeInMillis - now.timeInMillis
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val id = runtime.scheduleActionsDelay("timeTask-${hour}-${minute}-${System.currentTimeMillis()}", delay, actions)
                    ModuleResult.Scheduled("scheduled at ${sdf.format(target.time)} (delay ${delay} ms), id=$id")
                }

                "wait" -> {
                    val secs = match.groups.getOrNull(0)?.toLongOrNull() ?: return ModuleResult.Error("bad wait")
                    val delay = secs * 1000L
                    val id = runtime.scheduleActionsDelay("waitTask-${System.currentTimeMillis()}", delay, actions)
                    ModuleResult.Scheduled("scheduled after ${secs}s, id=$id")
                }

                "exists" -> {
                    val p = match.groups.getOrNull(0)?.trim() ?: return ModuleResult.Error("bad path")
                    val found = resolveInWork(ctx, p)
                    if (found != null) {
                        runtime.executeActionsNow(actions)
                        ModuleResult.Executed("exists: $p")
                    } else ModuleResult.Error("not found: $p")
                }

                "size" -> {
                    val cmp = match.groups.getOrNull(0) ?: return ModuleResult.Error("bad comparator")
                    val numStr = match.groups.getOrNull(1) ?: return ModuleResult.Error("bad number")
                    val p = match.groups.getOrNull(2) ?: return ModuleResult.Error("bad path")
                    val needed = parseBytesWithSuffix(numStr)
                    val found = resolveInWork(ctx, p) ?: return ModuleResult.Error("not found: $p")
                    val size = calculateSize(found)
                    val ok = when (cmp) {
                        ">" -> size > needed
                        "<" -> size < needed
                        ">=" -> size >= needed
                        "<=" -> size <= needed
                        "=" -> size == needed
                        else -> false
                    }
                    if (ok) {
                        runtime.executeActionsNow(actions)
                        ModuleResult.Executed("size check passed ($size bytes for $p)")
                    } else ModuleResult.Error("size check failed ($size bytes for $p, need $cmp $needed)")
                }

                else -> ModuleResult.Error("unsupported match type")
            }
        } catch (t: Throwable) {
            ModuleResult.Error("module error")
        }
    }

    private fun calculateSize(doc: DocumentFile): Long {
        return try {
            if (doc.isDirectory) doc.listFiles().sumOf { calculateSize(it) } else doc.length()
        } catch (_: Throwable) { 0L }
    }
}
