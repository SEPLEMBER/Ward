package org.syndes.terminal

import android.app.Activity
import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

class Terminal {

    private val history = mutableListOf<String>()

    /**
     * Выполняет команду и возвращает текстовый результат.
     * Если команда открывает SettingsActivity - возвращает null (MainActivity не выводит ничего).
     */
    fun execute(commandRaw: String, ctx: Context): String? {
        val command = commandRaw.trim()
        if (command.isEmpty()) return ""

        // check wait mode
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val waitUntil = prefs.getLong("wait_until", 0L)
        val now = System.currentTimeMillis()
        if (now < waitUntil) {
            val rem = (waitUntil - now + 999) / 1000
            return "Wait active: $rem s remaining"
        }

        // сохраняем в историю (как введено)
        history.add(command)

        // разбиваем на токены
        val tokens = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""

        var cmdName = tokens[0].lowercase()
        var args = tokens.drop(1)

        // Загружаем алиасы
        val aliasesStr = prefs.getString("aliases", "") ?: ""
        val aliases = aliasesStr.split(";").filter { it.isNotBlank() }.associate {
            val parts = it.split("=", limit = 2)
            parts[0].trim().lowercase() to parts.getOrNull(1)?.trim().orEmpty()
        }

        // Подставляем алиас, если есть (простая подстановка первого токена)
        if (cmdName in aliases) {
            val aliasCmd = aliases[cmdName]!!
            val aliasTokens = aliasCmd.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (aliasTokens.isNotEmpty()) {
                cmdName = aliasTokens[0].lowercase()
                args = aliasTokens.drop(1) + args
            }
        }

        return try {
            when (cmdName) {
                "help" -> {
                    """
                    Available commands:
                      help            - show this help
                      about           - app info/version
                      echo <text>     - print text (supports > file for redirect)
                      open <app name> - launch app by visible name (e.g. open Minecraft)
                      open <file>     - open file in associated app (e.g. open file.txt)
                      launch <app>    - explicitly launch app (alias to open-app)
                      history         - show input history
                      clear           - clear terminal history (internal)
                      settings|console- open settings screen
                      clearwork       - clear work folder (SAF) configured in settings (recursive)
                      ls|dir          - list files in current directory
                      cd <dir>        - change directory (supports .., relative paths, 'home')
                      pwd             - print working directory path
                      cp <src> <dst>  - copy file/dir (supports relative paths)
                      mv <src> <dst>  - move file/dir (supports relative paths)
                      rm [-r] <file>  - remove file or dir (recursive with -r, supports paths)
                      mkdir <name>    - create directory (supports paths)
                      touch <name>    - create empty file (supports paths)
                      cat <file>      - display file contents (supports paths)
                      ln <src> <link> - create pseudo-link (copy) of file (supports paths)
                      wc <file>       - word count: lines, words, chars (supports paths)
                      head <file> [n] - show first n lines (default 10, supports paths)
                      tail <file> [n] - show last n lines (default 10, supports paths)
                      du <file|dir>   - show size in bytes (recursive for dirs, supports paths)
                      stat <path>     - show file/dir stats (size, type, modified)
                      find <name>     - find files by name in current dir
                      cmp <f1> <f2>   - compare two text files (line-by-line)
                      diff <f1> <f2>  - show differences with line numbers
                      replace <old> <new> <path> - replace text in file or recursively in directory
                      rename <old> <new> <path> - rename filenames in dir (substring replace; use {} in new for counter)
                      encrypt <password> <path> - encrypt text files (recursively if dir)
                      decrypt <password> <path> - decrypt text files (recursively if dir)
                      
                      !!! WARNING (ENCRYPT): encrypting large folders is CPU-intensive.
                      Please encrypt small folders or specific directories one-by-one.
                      Iterations = 1000 (LOW). This provides only limited KDF hardness.
                      Use a strong password — recommendation: minimum **8 different characters** (no enforcement).
                       (Encryption here is a convenience/bonus — responsibility for secure passwords is on the user.)
                      
                      wait <seconds>  - block commands for given seconds (persist until timeout)
                      pm list [user|system] - list installed packages (all or filtered)
                      pm install <apk>- install APK from work dir (supports paths)
                      pm uninstall <pkg|appname> - uninstall package or app by name
                      pm launch <pkg|appname> - launch package or app by name
                      date            - show current date/time
                      whoami          - show user info
                      uname           - show system info
                      uptime          - show system uptime
                      which <cmd>     - check if command exists
                      alias <name>=<command> - set alias
                      alias list      - list aliases
                      alias run <name>- run alias
                      unalias <name>  - remove alias
                      env             - show environment vars
                      sms [number] [text] - open SMS app (with number and text)
                      call <number>   - dial number
                      email [address] [subject] [body] - open email app (with address, subject, body)
                      browser [url]   - open browser (with URL)
                      search <query>  - web search query
                      contacts, alarm, calc, vpns, btss, wifi, bts, data, apm, snd, dsp, apps, stg, sec, loc, nfc, cam, clk, notif, acc, dev
                      
                    Script support (syd):
                      syd list                 - list scripts in work_dir/scripts
                      syd run <name>           - run script (name or name.syd)
                      syd stop <script-id>     - stop running script (id returned on start)
                      syd edit <name>          - open script for editing (if editor available)
                      syd validate <name>      - validate syntax using ScriptHandler
                      run <name>               - alias for 'syd run <name>'
                    """.trimIndent()
                }

                "about" -> {
                    "Info: Syndes Terminal v0.13"
                }

                // -------------------------
                // wait command
                // -------------------------
                "wait" -> {
                    if (args.isEmpty()) return "Usage: wait <seconds>"
                    val secs = args[0].toLongOrNull() ?: return "Error: invalid seconds"
                    val until = System.currentTimeMillis() + secs * 1000L
                    prefs.edit().putLong("wait_until", until).apply()
                    "Info: set wait for $secs s"
                }

                // -------------------------
                // Script commands: syd / run
                // -------------------------
                "syd", "script" -> {
                    val sub = args.getOrNull(0)?.lowercase()
                    if (sub == null) return "Usage: syd list|run <name>|stop <id>|edit <name>|validate <name>"
                    when (sub) {
                        "list" -> {
                            val root = getRootDir(ctx) ?: return "Error: work folder not set."
                            val scriptsDir = root.findFile("scripts")?.takeIf { it.isDirectory } ?: root
                            val files = scriptsDir.listFiles().filter { it.isFile }
                            if (files.isEmpty()) "No scripts found in ${scriptsDir.name ?: "work dir"}"
                            else files.joinToString("\n") { it.name ?: "?" }
                        }
                        "run" -> {
                            val name = args.drop(1).joinToString(" ").trim()
                            if (name.isEmpty()) return "Usage: syd run <scriptname>"
                            val scriptDoc = findScriptFile(ctx, name) ?: return "Error: script '$name' not found in work_dir/scripts or work root"
                            return try {
                                ScriptHandler.startScriptFromUri(ctx, scriptDoc.uri)
                            } catch (t: Throwable) {
                                "Error: failed to start script: ${t.message ?: "unknown"}"
                            }
                        }
                        "stop" -> {
                            val id = args.getOrNull(1) ?: return "Usage: syd stop <script-id>"
                            return try {
                                ScriptHandler.stopScript(id)
                            } catch (t: Throwable) {
                                "Error: failed to stop script: ${t.message ?: "unknown"}"
                            }
                        }
                        "edit" -> {
                            val name = args.drop(1).joinToString(" ").trim()
                            if (name.isEmpty()) return "Usage: syd edit <scriptname>"
                            val scriptDoc = findScriptFile(ctx, name) ?: return "Error: script '$name' not found"
                            return try {
                                val editIntent = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(scriptDoc.uri, "text/plain")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val pm = ctx.packageManager
                                val resolves = pm.queryIntentActivities(editIntent, PackageManager.MATCH_DEFAULT_ONLY)
                                if (resolves.isNotEmpty()) {
                                    ctx.startActivity(editIntent)
                                } else {
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(scriptDoc.uri, "text/plain")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    ctx.startActivity(viewIntent)
                                }
                                "Info: opened editor for ${scriptDoc.name}"
                            } catch (t: Throwable) {
                                "Error: cannot open editor for ${scriptDoc.name}"
                            }
                        }
                        "validate" -> {
                            val name = args.drop(1).joinToString(" ").trim()
                            if (name.isEmpty()) return "Usage: syd validate <scriptname>"
                            val scriptDoc = findScriptFile(ctx, name) ?: return "Error: script '$name' not found"
                            val content = try { ctx.contentResolver.openInputStream(scriptDoc.uri)?.bufferedReader()?.use { it.readText() } } catch (_: Throwable) { null }
                            if (content == null) return "Error: cannot read script"
                            return try {
                                ScriptHandler.validateScriptText(ctx, content)
                            } catch (t: Throwable) {
                                "Error: validation failed: ${t.message ?: "unknown"}"
                            }
                        }
                        else -> "Usage: syd list|run <name>|stop <id>|edit <name>|validate <name>"
                    }
                }

                "run" -> {
                    val name = args.joinToString(" ").trim()
                    if (name.isEmpty()) return "Usage: run <scriptname>"
                    val scriptDoc = findScriptFile(ctx, name) ?: return "Error: script '$name' not found"
                    return try {
                        ScriptHandler.startScriptFromUri(ctx, scriptDoc.uri)
                    } catch (t: Throwable) {
                        "Error: failed to start script: ${t.message ?: "unknown"}"
                    }
                }

                // -------------------------
                // Basic commands (files / apps / utils)
                // -------------------------
                "echo" -> {
                    if (args.contains(">")) {
                        val index = args.indexOf(">")
                        if (index == -1 || index == args.size - 1) return "Error: invalid redirect"
                        val text = args.subList(0, index).joinToString(" ")
                        val filePath = args[index + 1]
                        val (parent, fileName) = resolvePath(ctx, filePath, createDirs = true) ?: return "Error: invalid path"
                        val file = parent.findFile(fileName) ?: parent.createFile("text/plain", fileName)
                            ?: return "Error: cannot create file"
                        val out = ctx.contentResolver.openOutputStream(file.uri, "wt")
                            ?: return "Error: cannot open file for writing"
                        out.use { it.write(text.toByteArray()) }
                        "Info: wrote to $filePath"
                    } else {
                        args.joinToString(" ")
                    }
                }

                "open" -> {
                    if (args.isEmpty()) return "Error: specify app name or file path, e.g. open Minecraft or open file.txt"
                    val target = args.joinToString(" ")
                    val pkg = findPackageByName(ctx, target)
                    if (pkg != null) {
                        val pm = ctx.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            if (ctx !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(launchIntent)
                            "Info: launching app '$target' (package: $pkg)"
                        } else {
                            val fallback = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                `package` = pkg
                                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val resolveList = pm.queryIntentActivities(fallback, 0)
                            if (resolveList.isNotEmpty()) {
                                ctx.startActivity(fallback)
                                "Info: launching app '$target' (package: $pkg)"
                            } else {
                                "Error: cannot launch app '$target' (no launcher activity)"
                            }
                        }
                    } else {
                        // Пробуем как файл (поддержка home/relative)
                        val (parent, fileName) = resolvePath(ctx, target) ?: return "Error: invalid path"
                        val file = parent.findFile(fileName) ?: return "Error: no such file '$target'"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(file.uri, file.type ?: "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        "Info: opening file '$target'"
                    }
                }

                "launch" -> {
                    if (args.isEmpty()) return "Usage: launch <app name or package>"
                    val target = args.joinToString(" ")
                    val pkg = if (target.contains(".")) target else findPackageByName(ctx, target)
                    if (pkg == null) return "Error: app not found: $target"
                    val li = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "Error: no launch intent for $pkg"
                    if (ctx !is Activity) li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(li)
                    "Info: launching $pkg"
                }

                "history" -> {
                    if (history.isEmpty()) "(no history)"
                    else history.joinToString("\n")
                }

                "matrix" -> {
                    if (args.getOrNull(0)?.lowercase() == "fall") {
                        val intent = Intent(ctx, SettingsActivity::class.java)
                        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        null
                    } else "Unknown matrix command"
                }

                "clear" -> {
                    history.clear()
                    "Info: Screen cleared."
                }

                "settings", "console" -> {
                    val intent = Intent(ctx, SettingsActivity::class.java)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ctx.startActivity(intent)
                    null
                }
 
                    "tutorial" -> {
                        val intent = Intent(ctx, SettingsActivity::class.java) // TODO: switch to TutorialActivity when available
                        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        null
                    }

                      "exit" -> {
                          // spawn thread to shutdown after a short delay to allow UI to render the message
                          Thread {
                              try { Thread.sleep(300) } catch (_: Throwable) {}
                              // try graceful finish if we have an Activity
                              (ctx as? Activity)?.runOnUiThread {
                                  try { (ctx as Activity).finishAffinity() } catch (_: Throwable) {}
                              }
                              try { Thread.sleep(200) } catch (_: Throwable) {}
                              try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
                          }.start()
                          "Shutting down..."
                      }
  
                        "backup", "snapshot" -> {
                            if (args.isEmpty()) return "Usage: backup <path>"
                            val path = args.joinToString(" ")
                            val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                            val src = parent.findFile(name) ?: return "Error: source not found"
                            val root = getRootDir(ctx) ?: return "Error: work folder not set."
                            val backupRoot = root.findFile("SydBack") ?: root.createDirectory("SydBack") ?: return "Error: cannot create SydBack"
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
                            val destDir = backupRoot.createDirectory("${name}_$stamp") ?: return "Error: cannot create snapshot dir"
                            val copied = recursiveCopySAF(ctx, src, destDir)
                            "Info: snapshot created: ${buildPath(destDir)} (files copied: $copied)"
                        }
  
                          "batchren" -> {
    if (args.size < 2) return "Usage: batchren <dir> <newPattern>"
    val dirPath = args[0]
    val pattern = args.drop(1).joinToString(" ")
    val (parent, dirName) = resolvePath(ctx, dirPath) ?: return "Error: invalid path"
    val dir = parent.findFile(dirName) ?: return "Error: no such directory '$dirPath'"
    if (!dir.isDirectory) return "Error: target is not a directory"
    val files = dir.listFiles().filter { !it.isDirectory }.sortedBy { it.lastModified() } // sorted by date
    var counter = 1
    var changed = 0
    for (f in files) {
        val ext = f.name?.substringAfterLast('.', "") ?: ""
        val base = if (pattern.contains("{}")) pattern.replace("{}", counter.toString()) else pattern + counter.toString()
        val newName = if (ext.isNotEmpty()) "$base.$ext" else base
        if (f.name == newName) { counter++; continue }
        // create new file and copy, then delete original
        val newFile = dir.createFile(f.type ?: "application/octet-stream", newName)
        if (newFile != null) {
            copyFile(ctx, f.uri, newFile.uri)
            f.delete()
            changed++
        }
        counter++
    }
    "Info: renamed $changed file(s)"
}

"trash" -> {
    if (args.isEmpty()) return "Usage: trash <path>"
    val path = args.joinToString(" ")
    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
    val target = parent.findFile(name) ?: return "Error: no such file '$path'"
    val root = getRootDir(ctx) ?: return "Error: work folder not set."
    val trash = root.findFile(".syndes_trash") ?: root.createDirectory(".syndes_trash") ?: return "Error: cannot create trash"
    val dest = trash.createDirectory((name ?: "item") + "_" + SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())) ?: trash
    val moved = recursiveCopySAF(ctx, target, dest)
    target.delete()
    "Info: moved to trash ($moved file(s))"
 }
 
 "cleartrash" -> {
    val root = getRootDir(ctx) ?: return "Error: work folder not set."
    val trash = root.findFile(".syndes_trash") ?: return "Info: trash empty"
    recursiveDelete(trash)
    "Info: trash cleared"
}

"sha256", "md5" -> {
    if (args.isEmpty()) return "Usage: sha256 <path>"
    val path = args.joinToString(" ")
    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
    val f = parent.findFile(name) ?: return "Error: file not found"
    val algo = if (cmdName == "sha256") "SHA-256" else "MD5"
    val hash = computeHashForSAF(ctx, f, algo) ?: return "Error: cannot read file"
    "$algo: $hash"
}

"pminfo", "pminfo" -> {
    if (args.isEmpty()) return "Usage: pminfo <package_or_appname>"
    val target = args.joinToString(" ")
    val pkg = if (target.contains(".")) target else findPackageByName(ctx, target) ?: return "Error: app not found"
    try {
        val pm = ctx.packageManager
        val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
        val sb = StringBuilder()
        sb.appendLine("Package: ${pi.packageName}")
        sb.appendLine("Version: ${pi.versionName} (code ${if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode})")
        sb.appendLine("ApplicationInfo uid: ${pi.applicationInfo.uid}")
        sb.appendLine("SourceDir: ${pi.applicationInfo.sourceDir}")
        val perms = pi.requestedPermissions ?: emptyArray()
        sb.appendLine("Requested permissions: ${perms.joinToString(", ")}")
        sb.toString()
    } catch (t: Throwable) {
        "Error: cannot get package info: ${t.message}"
    }
}

"which" -> {
    if (args.isEmpty()) return "Usage: which <command>"
    val cmd = args[0].lowercase()
    val known = listOf(/* same known list as before */)
    if (aliasesStr.split(";").any { it.split("=")[0].trim().lowercase() == cmd }) return "$cmd: alias"
    // check built-ins quickly
    if (listOf("help","about","echo","open","launch","history","clear","settings","ls","cd","pwd","cp","mv","rm","mkdir","touch","cat","wc","head","tail","du","stat","find","pm","date","uname","uptime","alias","unalias","env","sms","browser","search","mem","device","cmp","diff","replace","rename","backup","snapshot","trash","cleartrash","sha256","md5","pminfo","which","type","pkgof").contains(cmd)) {
        "$cmd: built-in command"
    } else {
        "$cmd: not found"
    }
}

"type" -> {
    if (args.isEmpty()) return "Usage: type <path>"
    val path = args.joinToString(" ")
    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
    val f = parent.findFile(name) ?: return "Error: not found"
    val sb = StringBuilder()
    sb.appendLine("Name: ${f.name}")
    sb.appendLine("Type: ${if (f.isDirectory) "directory" else "file"}")
    sb.appendLine("MIME: ${f.type ?: "unknown"}")
    sb.appendLine("Size: ${f.length()} bytes")
    sb.appendLine("Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(f.lastModified()))}")
    sb.toString()
}

// resolver: find package by visible name quickly
"pkgof", "findpkg" -> {
    if (args.isEmpty()) return "Usage: pkgof <app visible name or partial package>"
    val name = args.joinToString(" ")
    val pkg = findPackageByName(ctx, name) ?: return "Error: not found"
    "Package: $pkg"
}

"batchedit" -> {
    if (args.size < 3) return "Usage: batchedit <old> <new> <dir> [--dry]"
    val old = args[0]; val new = args[1]; val dirPath = args[2]
    val dry = args.contains("--dry")
    val (p,dn) = resolvePath(ctx, dirPath) ?: return "Error: invalid path"
    val dir = p.findFile(dn) ?: return "Error: dir not found"
    if (!dir.isDirectory) return "Error: target not a directory"
    val files = dir.listFiles().filter { !it.isDirectory }
    var changed = 0
    for (f in files) {
        if (dry) {
            // just count potential changes
            val text = try { ctx.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Throwable) { "" }
            if (text.contains(old)) changed++
        } else {
            if (replaceInFile(ctx, f, old, new)) changed++
        }
    }
    if (dry) "Dry-run: $changed file(s) would change" else "Info: changed $changed file(s)"
}

"ps", "top" -> {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "Error: cannot access ActivityManager"
    val procs = am.runningAppProcesses ?: return "Error: cannot get processes"
    val sb = StringBuilder()
    val list = procs.map { it }.sortedByDescending { it.importance } // crude sort
    for (p in list.take(50)) {
        sb.appendLine("PID:${p.pid} Name:${p.processName} pkg:${p.pkgList?.joinToString(",")}")
    }
    sb.toString()
}

"sysclipboard" -> {
    if (args.isEmpty()) return "Usage: sysclipboard get|set <text>"
    val sub = args[0].lowercase()
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return "Error: no clipboard"
    when (sub) {
        "get" -> {
            val clip = cm.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
            text
        }
        "set" -> {
            val text = args.drop(1).joinToString(" ")
            val clipData = android.content.ClipData.newPlainText("syndes", text)
            cm.setPrimaryClip(clipData)
            "Info: clipboard set"
        }
        else -> "Usage: clipboard get|set <text>"
    }
}

"preview" -> {
    if (args.isEmpty()) return "Usage: preview <path> [lines]"
    val path = args[0]
    val lines = if (args.size > 1) args[1].toIntOrNull() ?: 20 else 20
    val (p,nm) = resolvePath(ctx, path) ?: return "Error: invalid path"
    val f = p.findFile(nm) ?: return "Error: not found"
    if (f.isDirectory) return "Error: cannot preview directory"
    val mime = f.type ?: ""
    try {
        ctx.contentResolver.openInputStream(f.uri)?.use { ins ->
            if (mime.startsWith("text") || nm.endsWith(".txt") || nm.endsWith(".md") || nm.endsWith(".log")) {
                val txt = ins.bufferedReader().use { it.lineSequence().take(lines).joinToString("\n") }
                txt
            } else if (mime.startsWith("image")) {
                // get image bounds
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeStream(ins, null, opts)
                "Image: ${opts.outWidth}x${opts.outHeight}, MIME: ${opts.outMimeType}"
            } else {
                "Preview not supported for MIME: $mime"
            }
        } ?: "Error: cannot open file"
    } catch (t: Throwable) { "Error: preview failed: ${t.message}" }
}

"filekey", "apkkey" -> {
    if (args.isEmpty()) return "Usage: filekey <apk_path>"
    val path = args.joinToString(" ")
    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
    val apk = parent.findFile(name) ?: return "Error: apk not found"
    // copy to cache
    val tmp = try {
        val cf = java.io.File(ctx.cacheDir, "syndes_tmp_${System.currentTimeMillis()}.apk")
        ctx.contentResolver.openInputStream(apk.uri)?.use { ins -> cf.outputStream().use { it.copyFrom(ins) } }
        cf
    } catch (t: Throwable) { null }
    if (tmp == null) return "Error: cannot copy apk to cache"
    try {
        val pm = ctx.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pi = pm.getPackageArchiveInfo(tmp.absolutePath, flags) ?: return "Error: cannot parse APK"
        // reflect signing info
        val sb = StringBuilder()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val signing = pi.signingInfo
            val certs = signing?.apkContentsSigners ?: signing?.signingCertificateHistory ?: emptyArray()
            for ((i,c) in certs.withIndex()) {
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(c.toByteArray())
                sb.appendLine("Cert#$i SHA-256: ${hash.toHexString()}")
            }
        } else {
        val sigs = pi.signatures ?: emptyArray()
            for ((i,s) in sigs.withIndex()) {
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                sb.appendLine("Signature#$i SHA-256: ${hash.toHexString()}")
            }
        }
        sb.toString()
    } catch (t: Throwable) {
        "Error: cannot inspect apk: ${t.message}"
    } finally {
        try { tmp.delete() } catch (_: Throwable) {}
    }
}
                          
                          
                          
  
                "delete all (y)" -> {
                    val root = getRootDir(ctx) ?: return "Error: work folder not set."
                    var deleted = 0
                    var failed = 0
                    val children = root.listFiles()
                    for (child in children) {
                        try {
                            recursiveDelete(child)
                            deleted++
                        } catch (t: Throwable) {
                            failed++
                        }
                    }
                    when {
                        deleted == 0 && failed == 0 -> "Info: work folder is empty."
                        failed == 0 -> "Info: deleted $deleted item(s) from work folder."
                        deleted > 0 -> "Info: deleted $deleted item(s), $failed failed to delete."
                        else -> "Error: failed to delete items (permission or filesystem error)."
                    }
                }

                "ls", "dir" -> {
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val files = current.listFiles()
                    if (files.isEmpty()) "Empty directory."
                    else files.joinToString("\n") { it.name ?: "?" }
                }

                "cd" -> {
                    val path = if (args.isEmpty()) "home" else args.joinToString(" ")
                    if (path == "home") {
                        val root = getRootDir(ctx) ?: return "Error: work folder not set."
                        setCurrentDir(ctx, root)
                        "Info: changed to ${buildPath(root)}"
                    } else {
                        val resolved = resolvePath(ctx, path, isDir = true) ?: return "Error: invalid path"
                        val (newDir, _) = resolved
                        setCurrentDir(ctx, newDir)
                        "Info: changed to ${buildPath(newDir)}"
                    }
                }

                "pwd" -> {
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    buildPath(current)
                }

                "cp" -> {
                    if (args.size < 2) return "Error: cp <source> <dest>"
                    val srcPath = args[0]
                    val destPath = args[1]
                    val (srcParent, srcName) = resolvePath(ctx, srcPath) ?: return "Error: invalid source path"
                    val src = srcParent.findFile(srcName) ?: return "Error: no source '$srcPath'"
                    val (destParent, destName) = resolvePath(ctx, destPath, createDirs = true) ?: return "Error: invalid dest path"
                    val dest = destParent.createFile(src.type ?: "*/*", destName) ?: return "Error: cannot create dest"
                    copyFile(ctx, src.uri, dest.uri)
                    "Info: copied $srcPath to $destPath"
                }

                "mv" -> {
                    if (args.size < 2) return "Error: mv <source> <dest>"
                    val srcPath = args[0]
                    val destPath = args[1]
                    val (srcParent, srcName) = resolvePath(ctx, srcPath) ?: return "Error: invalid source path"
                    val src = srcParent.findFile(srcName) ?: return "Error: no source '$srcPath'"
                    val (destParent, destName) = resolvePath(ctx, destPath, createDirs = true) ?: return "Error: invalid dest path"
                    val dest = destParent.createFile(src.type ?: "*/*", destName) ?: return "Error: cannot create dest"
                    copyFile(ctx, src.uri, dest.uri)
                    src.delete()
                    "Info: moved $srcPath to $destPath"
                }

                "rm" -> {
                    if (args.isEmpty()) return "Error: rm [-r] <path>"
                    val hasR = args.contains("-r")
                    val targetPath = args.firstOrNull { it != "-r" } ?: return "Error: specify path"
                    val (parent, targetName) = resolvePath(ctx, targetPath) ?: return "Error: invalid path"
                    val target = parent.findFile(targetName) ?: return "Error: no such file '$targetPath'"
                    if (target.isDirectory && !hasR) {
                        if (target.listFiles().isNotEmpty()) return "Error: directory not empty, use -r"
                    }
                    recursiveDelete(target)
                    "Info: deleted $targetPath"
                }

                "mkdir" -> {
                    if (args.isEmpty()) return "Error: mkdir <path>"
                    val dirPath = args[0]
                    val (parent, dirName) = resolvePath(ctx, dirPath, createDirs = true) ?: return "Error: invalid path"
                    parent.createDirectory(dirName) ?: return "Error: cannot create directory"
                    "Info: created directory $dirPath"
                }

                "touch" -> {
                    if (args.isEmpty()) return "Error: touch <path>"
                    val filePath = args[0]
                    val (parent, fileName) = resolvePath(ctx, filePath, createDirs = true) ?: return "Error: invalid path"
                    parent.createFile("text/plain", fileName) ?: return "Error: cannot create file"
                    "Info: created file $filePath"
                }

                "cat" -> {
                    if (args.isEmpty()) return "Error: cat <path>"
                    val filePath = args[0]
                    val (parent, fileName) = resolvePath(ctx, filePath) ?: return "Error: invalid path"
                    val file = parent.findFile(fileName) ?: return "Error: no such file '$filePath'"
                    val input = ctx.contentResolver.openInputStream(file.uri) ?: return "Error: cannot open file"
                    input.bufferedReader().use { it.readText() }
                }

                "ln" -> {
                    if (args.size < 2) return "Error: ln <source> <link>"
                    val srcPath = args[0]
                    val linkPath = args[1]
                    val (srcParent, srcName) = resolvePath(ctx, srcPath) ?: return "Error: invalid source path"
                    val src = srcParent.findFile(srcName) ?: return "Error: no source '$srcPath'"
                    val (linkParent, linkName) = resolvePath(ctx, linkPath, createDirs = true) ?: return "Error: invalid link path"
                    val link = linkParent.createFile(src.type ?: "*/*", linkName) ?: return "Error: cannot create link"
                    copyFile(ctx, src.uri, link.uri)
                    "Info: created link $linkPath to $srcPath"
                }

                "wc" -> {
                    if (args.isEmpty()) return "Error: wc <path>"
                    val filePath = args[0]
                    val (parent, fileName) = resolvePath(ctx, filePath) ?: return "Error: invalid path"
                    val file = parent.findFile(fileName) ?: return "Error: no such file '$filePath'"
                    val input = ctx.contentResolver.openInputStream(file.uri) ?: return "Error: cannot open file"
                    val text = input.bufferedReader().use { it.readText() }
                    val lines = text.lines().size
                    val words = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                    val chars = text.length
                    "$lines $words $chars $filePath"
                }

                "head" -> {
                    if (args.isEmpty()) return "Error: head <path> [n]"
                    val filePath = args[0]
                    val n = if (args.size > 1) args[1].toIntOrNull() ?: 10 else 10
                    val (parent, fileName) = resolvePath(ctx, filePath) ?: return "Error: invalid path"
                    val file = parent.findFile(fileName) ?: return "Error: no such file '$filePath'"
                    val input = ctx.contentResolver.openInputStream(file.uri) ?: return "Error: cannot open file"
                    input.bufferedReader().use { reader ->
                        (1..n).mapNotNull { reader.readLine() }.joinToString("\n")
                    }
                }

                "tail" -> {
                    if (args.isEmpty()) return "Error: tail <path> [n]"
                    val filePath = args[0]
                    val n = if (args.size > 1) args[1].toIntOrNull() ?: 10 else 10
                    val (parent, fileName) = resolvePath(ctx, filePath) ?: return "Error: invalid path"
                    val file = parent.findFile(fileName) ?: return "Error: no such file '$filePath'"
                    val input = ctx.contentResolver.openInputStream(file.uri) ?: return "Error: cannot open file"
                    val lines = input.bufferedReader().use { it.readLines() }
                    lines.takeLast(n).joinToString("\n")
                }

                "du" -> {
                    if (args.isEmpty()) return "Error: du <path>"
                    val path = args[0]
                    val (parent, entryName) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(entryName) ?: return "Error: no such file '$path'"
                    val size = calculateSize(target)
                    "${humanReadableBytes(size)} ($size bytes) $path"
                }

                "stat" -> {
                    if (args.isEmpty()) return "Error: stat <path>"
                    val path = args[0]
                    val (parent, entryName) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(entryName) ?: return "Error: no such file '$path'"
                    val size = target.length()
                    val type = target.type ?: "unknown"
                    val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(target.lastModified()))
                    "Size: ${humanReadableBytes(size)} ($size bytes)\nType: $type\nModified: $modified"
                }

                "find" -> {
                    if (args.isEmpty()) return "Error: find <name>"
                    val searchName = args.joinToString(" ").lowercase()
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val results = current.listFiles().filter { it.name?.lowercase()?.contains(searchName) == true }.joinToString("\n") { it.name ?: "?" }
                    if (results.isEmpty()) "No matches found." else results
                }

                // compare files line-by-line
                "cmp" -> {
                    if (args.size < 2) return "Usage: cmp <file1> <file2>"
                    val f1path = args[0]; val f2path = args[1]
                    val (p1, n1) = resolvePath(ctx, f1path) ?: return "Error: invalid path $f1path"
                    val (p2, n2) = resolvePath(ctx, f2path) ?: return "Error: invalid path $f2path"
                    val f1 = p1.findFile(n1) ?: return "Error: no such file '$f1path'"
                    val f2 = p2.findFile(n2) ?: return "Error: no such file '$f2path'"
                    val lines1 = try { ctx.contentResolver.openInputStream(f1.uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList() } catch (_: Throwable) { return "Error: cannot read $f1path" }
                    val lines2 = try { ctx.contentResolver.openInputStream(f2.uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList() } catch (_: Throwable) { return "Error: cannot read $f2path" }
                    val maxLines = max(lines1.size, lines2.size)
                    for (i in 0 until maxLines) {
                        val a = lines1.getOrNull(i)
                        val b = lines2.getOrNull(i)
                        if (a != b) {
                            return "Differ at line ${i+1}:\n< ${a ?: "<no line>"}\n> ${b ?: "<no line>"}"
                        }
                    }
                    "Equal"
                }

                // diff: show simple differences with line numbers
                "diff" -> {
                    if (args.size < 2) return "Usage: diff <file1> <file2>"
                    val f1path = args[0]; val f2path = args[1]
                    val (p1, n1) = resolvePath(ctx, f1path) ?: return "Error: invalid path $f1path"
                    val (p2, n2) = resolvePath(ctx, f2path) ?: return "Error: invalid path $f2path"
                    val f1 = p1.findFile(n1) ?: return "Error: no such file '$f1path'"
                    val f2 = p2.findFile(n2) ?: return "Error: no such file '$f2path'"
                    val lines1 = try { ctx.contentResolver.openInputStream(f1.uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList() } catch (_: Throwable) { return "Error: cannot read $f1path" }
                    val lines2 = try { ctx.contentResolver.openInputStream(f2.uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList() } catch (_: Throwable) { return "Error: cannot read $f2path" }
                    val sb = StringBuilder()
                    val maxLines = max(lines1.size, lines2.size)
                    for (i in 0 until maxLines) {
                        val a = lines1.getOrNull(i)
                        val b = lines2.getOrNull(i)
                        if (a != b) {
                            sb.appendLine("Line ${i+1}:")
                            sb.appendLine("  - ${a ?: "<no line>"}")
                            sb.appendLine("  + ${b ?: "<no line>"}")
                        }
                    }
                    if (sb.isEmpty()) "No differences" else sb.toString()
                }

                // replace old->new in file or recursively in directory
                "replace" -> {
                    if (args.size < 3) return "Usage: replace <old> <new> <path>"
                    val old = args[0]
                    val new = args[1]
                    val path = args.drop(2).joinToString(" ")
                    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(name) ?: return "Error: no such file/dir '$path'"
                    val changed = if (target.isDirectory) {
                        replaceInDirRecursive(ctx, target, old, new)
                    } else {
                        val ok = replaceInFile(ctx, target, old, new)
                        if (ok) 1 else 0
                    }
                    "Info: replaced in $changed file(s)"
                }

                // rename filenames in directory (substring replacement in filenames)
                "rename" -> {
                    if (args.size < 3) return "Usage: rename <old> <new> <dir>"
                    val old = args[0]
                    val new = args[1]
                    val path = args.drop(2).joinToString(" ")
                    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val dir = parent.findFile(name) ?: return "Error: no such directory '$path'"
                    if (!dir.isDirectory) return "Error: target is not a directory"
                    var counter = 1
                    var changed = 0
                    for (child in dir.listFiles()) {
                        val nm = child.name ?: continue
                        if (nm.contains(old)) {
                            val repl = if (new.contains("{}")) new.replace("{}", counter.toString()) else nm.replace(old, new)
                            // try to rename: SAF doesn't have rename API everywhere; create new file and copy
                            val mime = child.type ?: "application/octet-stream"
                            val newFile = dir.createFile(mime, repl)
                            if (newFile != null) {
                                copyFile(ctx, child.uri, newFile.uri)
                                child.delete()
                                changed++
                                counter++
                            }
                        }
                    }
                    "Info: renamed $changed file(s)"
                }

                // encrypt / decrypt (text files only)
                "encrypt" -> {
                    if (args.size < 2) return "Usage: encrypt <password> <path>"
                    val password = args[0]
                    val path = args.drop(1).joinToString(" ")
                    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(name) ?: return "Error: no such file/dir '$path'"
                    val processed = if (target.isDirectory) {
                        encryptInDir(ctx, password, target)
                    } else {
                        if (encryptFile(ctx, password, target)) 1 else 0
                    }
                    "Info: encrypted $processed file(s)"
                }

                "decrypt" -> {
                    if (args.size < 2) return "Usage: decrypt <password> <path>"
                    val password = args[0]
                    val path = args.drop(1).joinToString(" ")
                    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(name) ?: return "Error: no such file/dir '$path'"
                    val processed = if (target.isDirectory) {
                        decryptInDir(ctx, password, target)
                    } else {
                        if (decryptFile(ctx, password, target)) 1 else 0
                    }
                    "Info: decrypted $processed file(s)"
                }

                "alias" -> {
                    if (args.isEmpty()) return "Usage: alias <name>=<command> | alias list | alias run <name>"
                    val sub = args[0]
                    when (sub) {
                        "list" -> {
                            val curr = prefs.getString("aliases", "") ?: ""
                            if (curr.isBlank()) "No aliases" else curr.split(";").filter { it.isNotBlank() }.joinToString("\n")
                        }
                        "run" -> {
                            val name = args.drop(1).joinToString(" ").trim().lowercase()
                            if (name.isEmpty()) return "Usage: alias run <name>"
                            val curr = prefs.getString("aliases", "") ?: ""
                            val map = curr.split(";").filter { it.isNotBlank() }.associate {
                                val parts = it.split("=", limit = 2)
                                parts[0].trim().lowercase() to parts.getOrNull(1)?.trim().orEmpty()
                            }
                            val cmd = map[name] ?: return "Error: alias '$name' not found"
                            return try {
                                execute(cmd, ctx)
                            } catch (t: Throwable) {
                                "Error: failed to run alias: ${t.message ?: "unknown"}"
                            }
                        }
                        else -> {
                            val aliasStr = args.joinToString(" ")
                            val parts = aliasStr.split("=", limit = 2)
                            if (parts.size < 2) return "Error: invalid format <name>=<command>"
                            val aliasName = parts[0].trim().lowercase()
                            val aliasCmd = parts[1].trim()
                            val currentAliases = prefs.getString("aliases", "") ?: ""
                            val newAliasesList = currentAliases.split(";").filter { it.isNotBlank() }.toMutableList()
                            newAliasesList.removeAll { it.split("=")[0].trim().lowercase() == aliasName }
                            newAliasesList.add("$aliasName=$aliasCmd")
                            val newAliases = newAliasesList.joinToString(";")
                            prefs.edit().putString("aliases", newAliases).apply()
                            "Info: alias set: $aliasName=$aliasCmd"
                        }
                    }
                }

                "unalias" -> {
                    if (args.isEmpty()) return "Error: unalias <name>"
                    val aliasName = args.joinToString(" ").trim().lowercase()
                    val currentAliases = prefs.getString("aliases", "") ?: ""
                    val newAliasesList = currentAliases.split(";").filter { it.isNotBlank() }.toMutableList()
                    val removed = newAliasesList.removeAll { it.split("=")[0].trim().lowercase() == aliasName }
                    val newAliases = newAliasesList.joinToString(";")
                    prefs.edit().putString("aliases", newAliases).apply()
                    if (removed) "Info: alias $aliasName removed" else "Info: alias $aliasName not found"
                }

                "env" -> {
                    prefs.all.entries.joinToString("\n") { "${it.key}=${it.value}" }
                }

                // Communication / quick apps
                "sms" -> {
                    val intent = if (args.isEmpty()) {
                        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) }
                    } else {
                        val number = args[0]
                        val text = args.drop(1).joinToString(" ")
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                            putExtra("sms_body", text)
                        }
                    }
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening SMS app"
                }

                "call" -> {
                    if (args.isEmpty()) return "Error: call <number>"
                    val number = args[0]
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: dialing $number"
                }

                "email" -> {
                    val intent = if (args.isEmpty()) {
                        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_EMAIL) }
                    } else {
                        val address = args[0]
                        val subject = if (args.size > 1) args[1] else ""
                        val body = if (args.size > 2) args.drop(2).joinToString(" ") else ""
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")).apply {
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                    }
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening email app"
                }

                "browser" -> {
                    // ensure scheme present
                    var url = if (args.isEmpty()) "https://" else args.joinToString(" ")
                    if (!url.contains("://")) url = "https://$url"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(intent)
                        "Info: opening browser"
                    } catch (ise: Exception) {
                        "Error: no activity found to handle URL"
                    }
                }

                "search" -> {
                    if (args.isEmpty()) return "Error: search <query>"
                    val query = args.joinToString(" ")
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, query)
                    }
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: searching '$query'"
                }

                "contacts" -> {
                    val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening contacts"
                }

                "alarm" -> {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening alarm app"
                }

                "calc" -> {
                    val pkg = findPackageByName(ctx, "calculator")
                    if (pkg == null) return "Error: calculator app not found"
                    val launchIntent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        if (ctx !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(launchIntent)
                        "Info: opening calculator"
                    } else {
                        "Error: cannot launch calculator"
                    }
                }

                "pm" -> {
                    if (args.isEmpty()) return "Error: pm list [user|system]|install <apk>|uninstall <pkg>|launch <pkg>"
                    when (args[0].lowercase()) {
                        "list" -> {
                            val filter = if (args.size > 1) args[1].lowercase() else "all"
                            val pm = ctx.packageManager
                            val packages = pm.getInstalledPackages(0)
                            val filtered = when (filter) {
                                "user" -> packages.filter { pm.getApplicationInfo(it.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                                "system" -> packages.filter { pm.getApplicationInfo(it.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
                                else -> packages
                            }
                            filtered.joinToString("\n") { it.packageName }
                        }
                        "install" -> {
                            if (args.size < 2) return "Error: pm install <apk>"
                            val apkPath = args[1]
                            val (parent, apkName) = resolvePath(ctx, apkPath) ?: return "Error: invalid path"
                            val apkFile = parent.findFile(apkName) ?: return "Error: no such APK '$apkPath'"
                            if (!apkName.endsWith(".apk")) return "Error: not an APK file"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(apkFile.uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                            "Info: installing $apkPath"
                        }
                        "uninstall" -> {
                            if (args.size < 2) return "Error: pm uninstall <pkg or appname>"
                            val target = args[1]
                            val pkg = if (target.contains(".")) target else findPackageByName(ctx, target) ?: return "Error: app not found: $target"
                            val intent = Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:$pkg")
                                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                            "Info: uninstalling $pkg"
                        }
                        "launch" -> {
                            if (args.size < 2) return "Error: pm launch <pkg or appname>"
                            val target = args[1]
                            val pkg = if (target.contains(".")) target else findPackageByName(ctx, target) ?: return "Error: app not found: $target"
                            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                if (ctx !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(launchIntent)
                                "Info: launching $pkg"
                            } else {
                                "Error: no launch intent for $pkg"
                            }
                        }
                        else -> "Error: unknown pm subcommand '${args[0]}'"
                    }
                }

                "date" -> {
                    SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy").format(Date())
                }

                "whoami" -> {
                    Process.myUserHandle().toString()
                }

                "uname" -> {
                    "Android ${Build.VERSION.RELEASE} ${Build.MODEL} ${Build.MANUFACTURER}"
                }

                "uptime" -> {
                    val uptimeMillis = SystemClock.elapsedRealtime()
                    val days = (uptimeMillis / (1000 * 60 * 60 * 24)).toInt()
                    val hours = ((uptimeMillis / (1000 * 60 * 60)) % 24).toInt()
                    val minutes = ((uptimeMillis / (1000 * 60)) % 60).toInt()
                    val seconds = ((uptimeMillis / 1000) % 60).toInt()
                    "$days days, $hours:$minutes:$seconds"
                }

                "which" -> {
                    if (args.isEmpty()) return "Error: which <command>"
                    val cmd = args[0].lowercase()
                    val knownCommands = listOf("help", "about", "echo", "open", "launch", "history", "clear", "settings", "console", "clearwork",
                        "ls", "dir", "cd", "pwd", "cp", "mv", "rm", "mkdir", "touch", "cat", "ln", "wc", "head", "tail", "du", "stat", "find",
                        "pm", "date", "whoami", "uname", "uptime", "which", "alias", "unalias", "env",
                        "sms", "call", "email", "browser", "search", "contacts", "alarm", "calc",
                        "vpns", "btss", "wifi", "bts", "data", "apm", "snd", "dsp", "apps", "stg", "sec", "loc", "nfc", "cam", "clk",
                        "notif", "acc", "dev", "syd", "run", "mem", "device", "cmp", "diff", "replace", "rename", "encrypt", "decrypt", "matrix")
                    if (knownCommands.contains(cmd)) {
                        "$cmd: built-in command"
                    } else {
                        "$cmd: not found"
                    }
                }

                "vpns" -> {
                    val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening VPN settings"
                }

                "btss" -> {
                    val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening battery settings"
                }

                "wifi" -> {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening Wi-Fi settings"
                }

                "bts" -> {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening Bluetooth settings"
                }

                "data" -> {
                    val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening mobile data settings"
                }

                "apm" -> {
                    val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening airplane mode settings"
                }

                "snd" -> {
                    val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening sound settings"
                }

                "dsp" -> {
                    val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening display settings"
                }

                "apps" -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening app settings"
                }

                "stg" -> {
                    val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening storage settings"
                }

                "sec" -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    }
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening security/app details"
                }

                "loc" -> {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening location settings"
                }

                "nfc" -> {
                    val intent = Intent(Settings.ACTION_NFC_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening NFC settings"
                }

                "cam" -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening camera"
                }

                "clk" -> {
                    val intent = Intent(Settings.ACTION_DATE_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening date/time settings"
                }

                "notif" -> {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening notification settings"
                }

                "acc" -> {
                    val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening account settings"
                }

                "dev" -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening developer settings"
                }

                // -------------------------
                // Extras: mem, device
                // -------------------------
                "mem" -> {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        ?: return "Error: cannot get ActivityManager"
                    val pkg = args.getOrNull(0)
                    if (pkg.isNullOrBlank()) {
                        val memInfo = ActivityManager.MemoryInfo()
                        am.getMemoryInfo(memInfo)
                        "AvailMem: ${humanReadableBytes(memInfo.availMem)}\nTotalMem: ${humanReadableBytes(memInfo.totalMem)}\nLowMemory: ${memInfo.lowMemory}\nThreshold: ${humanReadableBytes(memInfo.threshold)}"
                    } else {
                        val running = am.runningAppProcesses ?: return "Error: cannot get running processes"
                        val proc = running.firstOrNull { it.pkgList?.contains(pkg) == true || it.processName.equals(pkg, ignoreCase = true) }
                        val pid = proc?.pid
                        if (pid == null) return "Error: process for package not found (not running)"
                        val pmi = am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()
                            ?: return "Error: cannot get process memory info"
                        // pmi values are in KB
                        "PSS: ${pmi.totalPss} KB (${humanReadableBytes(pmi.totalPss.toLong() * 1024)})\nPrivateDirty: ${pmi.totalPrivateDirty} KB\nSharedDirty: ${pmi.totalSharedDirty} KB"
                    }
                }

                "device" -> {
                    val rt = Runtime.getRuntime()
                    val sb = StringBuilder()
                    sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
                    sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                    sb.appendLine("CPU cores: ${rt.availableProcessors()}")
                    sb.appendLine("JVM max mem: ${humanReadableBytes(rt.maxMemory())}, total: ${humanReadableBytes(rt.totalMemory())}, free: ${humanReadableBytes(rt.freeMemory())}")
                    sb.toString()
                }

                else -> {
                    "Unknown command: $command"
                }
            }
        } catch (t: Throwable) {
            "Error: ${t.message ?: "execution failed"}"
        }
    }

    // ---------------------------
    // Helpers for script discovery & replace & encrypt
    // ---------------------------
    private fun findScriptFile(ctx: Context, name: String): DocumentFile? {
        val root = getRootDir(ctx) ?: return null
        val scriptsDir = root.findFile("scripts")?.takeIf { it.isDirectory } ?: root

        if (name.contains("/")) {
            val full = resolvePath(ctx, name)
            if (full != null) {
                val (parent, fileName) = full
                val f = parent.findFile(fileName)
                if (f != null && f.isFile) return f
            }
        }

        val candidates = mutableListOf<String>()
        if (name.contains('.')) {
            candidates.add(name)
        } else {
            listOf(".syd", ".cyd", ".sydscrypt", ".txt").forEach { ext ->
                candidates.add("$name$ext")
            }
            candidates.add(name)
        }

        for (cand in candidates) {
            val f = scriptsDir.findFile(cand)
            if (f != null && f.isFile) return f
        }

        for (cand in candidates) {
            val f = root.findFile(cand)
            if (f != null && f.isFile) return f
        }

        val match = scriptsDir.listFiles().firstOrNull { it.name?.lowercase()?.contains(name.lowercase()) == true }
        if (match != null && match.isFile) return match

        return null
    }

    // Replace in a single file. Returns true if replaced (found old substring and successfully wrote).
    private fun replaceInFile(ctx: Context, doc: DocumentFile, old: String, new: String): Boolean {
        return try {
            val input = ctx.contentResolver.openInputStream(doc.uri) ?: return false
            val text = input.bufferedReader().use { it.readText() }
            if (!text.contains(old)) return false
            val replaced = text.replace(old, new)
            val out = ctx.contentResolver.openOutputStream(doc.uri, "wt") ?: return false
            out.use { it.write(replaced.toByteArray()) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun replaceInDirRecursive(ctx: Context, dir: DocumentFile, old: String, new: String): Int {
        var changed = 0
        try {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    changed += replaceInDirRecursive(ctx, child, old, new)
                } else {
                    if (replaceInFile(ctx, child, old, new)) changed++
                }
            }
        } catch (_: Throwable) { }
        return changed
    }
    
    // Helper: recursive copy (SAF) — возвращает кол-во файлов скопированных
private fun recursiveCopySAF(ctx: Context, src: DocumentFile, destDir: DocumentFile): Int {
    var count = 0
    if (src.isDirectory) {
        for (child in src.listFiles()) {
            if (child.isDirectory) {
                val newDir = destDir.createDirectory(child.name ?: "dir") ?: continue
                count += recursiveCopySAF(ctx, child, newDir)
            } else {
                val newFile = destDir.createFile(child.type ?: "application/octet-stream", child.name ?: "file")
                if (newFile != null) {
                    copyFile(ctx, child.uri, newFile.uri)
                    count++
                }
            }
        }
    } else {
        val newFile = destDir.createFile(src.type ?: "application/octet-stream", src.name ?: "file")
        if (newFile != null) {
            copyFile(ctx, src.uri, newFile.uri)
            count++
        }
    }
    return count
}

// Helper: compute hash (MD5/SHA-256) for a DocumentFile. Returns hex string or null.
private fun computeHashForSAF(ctx: Context, doc: DocumentFile, algo: String): String? {
    return try {
        val md = java.security.MessageDigest.getInstance(algo)
        ctx.contentResolver.openInputStream(doc.uri)?.use { ins ->
            val buf = ByteArray(8192)
            var r: Int
            while (ins.read(buf).also { r = it } > 0) {
                md.update(buf, 0, r)
            }
        } ?: return null
        md.digest().toHexString()
    } catch (_: Throwable) {
        null
    }
}

// ByteArray -> hex
private fun ByteArray.toHexString(): String {
    val sb = StringBuilder()
    for (b in this) sb.append(String.format("%02x", b))
    return sb.toString()
}

// copy InputStream -> File (helper used in filekey)
private fun java.io.OutputStream.copyFrom(input: java.io.InputStream) {
    val buf = ByteArray(8192)
    var r: Int
    while (input.read(buf).also { r = it } > 0) {
        this.write(buf, 0, r)
    }
}

// convenient extension to write InputStream -> File
private fun java.io.File.outputStream(): java.io.FileOutputStream = java.io.FileOutputStream(this)

// small wrapper for create+copy used earlier (filekey)
private fun java.io.File.writeFromInputStream(ins: java.io.InputStream) {
    this.outputStream().use { out -> ins.copyTo(out) }
}

    // ---------------------------
    // Encrypt / Decrypt helpers
    // ---------------------------
    private fun isProbablyTextFile(ctx: Context, file: DocumentFile): Boolean {
        return try {
            ctx.contentResolver.openInputStream(file.uri)?.use { stream ->
                val buf = ByteArray(1024)
                val read = stream.read(buf)
                if (read <= 0) return true
                for (i in 0 until read) {
                    if (buf[i].toInt() == 0) return false
                }
                true
            } ?: false
        } catch (_: Throwable) { false }
    }

    private fun encryptFile(ctx: Context, password: String, file: DocumentFile): Boolean {
        return try {
            if (!isProbablyTextFile(ctx, file)) return false
            val input = ctx.contentResolver.openInputStream(file.uri) ?: return false
            val text = input.bufferedReader().use { it.readText() }
            val enc = Secure.encrypt(password, text)
            val out = ctx.contentResolver.openOutputStream(file.uri, "wt") ?: return false
            out.use { it.write(enc.toByteArray()) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun decryptFile(ctx: Context, password: String, file: DocumentFile): Boolean {
        return try {
            val input = ctx.contentResolver.openInputStream(file.uri) ?: return false
            val text = input.bufferedReader().use { it.readText() }
            // try decrypt; if fails - propagate false
            val dec = try { Secure.decrypt(password, text) } catch (_: Throwable) { return false }
            val out = ctx.contentResolver.openOutputStream(file.uri, "wt") ?: return false
            out.use { it.write(dec.toByteArray()) }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun encryptInDir(ctx: Context, password: String, dir: DocumentFile): Int {
        var changed = 0
        try {
            for (child in dir.listFiles()) {
                if (child.isDirectory) changed += encryptInDir(ctx, password, child)
                else {
                    if (encryptFile(ctx, password, child)) changed++
                }
            }
        } catch (_: Throwable) { }
        return changed
    }

    private fun decryptInDir(ctx: Context, password: String, dir: DocumentFile): Int {
        var changed = 0
        try {
            for (child in dir.listFiles()) {
                if (child.isDirectory) changed += decryptInDir(ctx, password, child)
                else {
                    if (decryptFile(ctx, password, child)) changed++
                }
            }
        } catch (_: Throwable) { }
        return changed
    }

    // ---------------------------
    // Existing helper methods
    // ---------------------------
    private fun getRootDir(ctx: Context): DocumentFile? {
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("work_dir_uri", null) ?: return null
        return try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        }
    }

    private fun getCurrentDir(ctx: Context): DocumentFile? {
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("current_dir_uri", null) ?: return getRootDir(ctx)
        return try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        }
    }

    private fun setCurrentDir(ctx: Context, dir: DocumentFile) {
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_dir_uri", dir.uri.toString()).apply()
    }

    private fun buildPath(doc: DocumentFile): String {
        val path = mutableListOf<String>()
        var current: DocumentFile? = doc
        while (current != null) {
            val name = current.name ?: break
            path.add(0, name)
            current = current.parentFile
        }
        return if (path.isEmpty()) "/" else "/" + path.joinToString("/")
    }

    private fun recursiveDelete(doc: DocumentFile) {
        if (doc.isDirectory) {
            doc.listFiles().forEach { recursiveDelete(it) }
        }
        doc.delete()
    }

    private fun copyFile(ctx: Context, srcUri: Uri, destUri: Uri) {
        val input: InputStream? = ctx.contentResolver.openInputStream(srcUri)
        val output: OutputStream? = ctx.contentResolver.openOutputStream(destUri)
        input?.use { inp ->
            output?.use { out ->
                inp.copyTo(out)
            }
        }
    }

    private fun calculateSize(doc: DocumentFile): Long {
        if (doc.isDirectory) {
            return doc.listFiles().sumOf { calculateSize(it) }
        }
        return doc.length()
    }

    /**
     * Разрешает относительный путь в SAF.
     * Возвращает пару (родительская папка, имя файла/папки).
     * Если createDirs = true, создаёт промежуточные папки.
     * Если isDir = true, ожидает, что последний компонент - папка.
     *
     * Поддерживает абсолютные пути (начинающиеся с '/' или 'home/...'), иначе - относительные к current_dir.
     */
    private fun resolvePath(ctx: Context, path: String, createDirs: Boolean = false, isDir: Boolean = false): Pair<DocumentFile, String>? {
        if (path.isEmpty()) return null
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val current = getCurrentDir(ctx) ?: return null
        val root = getRootDir(ctx) ?: return null

        // Normalize separators and trim
        val raw = path.trim()
        val leadingSlash = raw.startsWith("/")
        var comps = raw.split("/").filter { it.isNotEmpty() }.toMutableList()

        var curDir: DocumentFile = current

        // Absolute path handling: leading "/" or initial "home"
        val absolute = leadingSlash || comps.firstOrNull()?.equals("home", ignoreCase = true) == true
        if (absolute) {
            curDir = root
            if (comps.firstOrNull()?.equals("home", ignoreCase = true) == true) {
                if (comps.isNotEmpty()) comps.removeAt(0)
            }
        }

        if (comps.isEmpty()) {
            return curDir to ""
        }

        // iterate through components except last (if not isDir)
        val endIndexExclusive = comps.size - if (isDir) 0 else 1
        for (i in 0 until endIndexExclusive) {
            val comp = comps[i]
            when (comp) {
                "." -> { /* nop */ }
                ".." -> {
                    val parent = curDir.parentFile
                    if (parent != null && parent.uri != root.uri) {
                        curDir = parent
                    }
                }
                else -> {
                    val sub = curDir.findFile(comp)
                    if (sub != null && sub.isDirectory) {
                        curDir = sub
                    } else if (createDirs) {
                        val newDir = curDir.createDirectory(comp) ?: return null
                        curDir = newDir
                    } else {
                        return null
                    }
                }
            }
        }

        val last = if (comps.isEmpty()) "" else comps.last()
        return curDir to last
    }

    /**
     * Ищет packageName по видимому имени приложения (label) или по packageName частично.
     * Сначала пробует точное совпадение label (ignoreCase), затем contains по label, затем по packageName.
     * Ищет только приложения с LAUNCHER activity (чтобы можно было запустить).
     */
    private fun findPackageByName(ctx: Context, appName: String): String? {
        val pm = ctx.packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)

        // 1) exact match by label
        for (ri in resolveInfos) {
            val label = ri.loadLabel(pm).toString()
            if (label.equals(appName, ignoreCase = true)) {
                return ri.activityInfo.packageName
            }
        }

        // 2) contains by label
        for (ri in resolveInfos) {
            val label = ri.loadLabel(pm).toString()
            if (label.contains(appName, ignoreCase = true)) {
                return ri.activityInfo.packageName
            }
        }

        // 3) contains by package name
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (pkg.contains(appName, ignoreCase = true)) {
                return pkg
            }
        }

        // not found
        return null
    }

    // ---------------------------
    // Small utilities
    // ---------------------------
    private fun humanReadableBytes(b: Long): String {
        val abs = if (b == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(b)
        if (abs < 1024) return "$b B"
        val value = abs.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var idx = 0
        var v = value
        while (v >= 1024 && idx < units.size - 1) {
            v /= 1024.0
            idx++
        }
        val sign = if (b < 0) "-" else ""
        return String.format("%s%.2f %s", sign, v, units[idx])
    }
}
