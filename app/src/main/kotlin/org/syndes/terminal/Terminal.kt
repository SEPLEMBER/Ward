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

class Terminal {

    private val history = mutableListOf<String>()

    /**
     * Выполняет команду и возвращает текстовый результат.
     * Если команда открывает SettingsActivity - возвращает null (MainActivity не выводит ничего).
     *
     * Важно: execute вызывается из MainActivity и передаётся Activity как context,
     * чтобы команды, запускающие Activity, работали корректно.
     */
    fun execute(commandRaw: String, ctx: Context): String? {
        val command = commandRaw.trim()
        if (command.isEmpty()) return ""

        // сохраняем в историю (как введено)
        history.add(command)

        // разбиваем на токены
        val tokens = command.split("\\s+".toRegex())
        var name = tokens[0].lowercase()
        var args = tokens.drop(1)

        // Загружаем алиасы
        val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val aliasesStr = prefs.getString("aliases", "") ?: ""
        val aliases = aliasesStr.split(";").filter { it.isNotBlank() }.associate {
            val parts = it.split("=", limit = 2)
            parts[0].trim().lowercase() to parts[1].trim()
        }

        // Подставляем алиас, если есть
        if (name in aliases) {
            val aliasCmd = aliases[name]!!
            val aliasTokens = aliasCmd.split("\\s+".toRegex())
            name = aliasTokens[0].lowercase()
            args = aliasTokens.drop(1) + args
        }

        return try {
            when (name) {
                "help" -> {
                    """
                    Available commands:
                      help            - show this help
                      about           - app info/version
                      echo <text>     - print text (supports > file for redirect)
                      open <app name> - launch app by visible name (e.g. open Minecraft)
                      open <file>     - open file in associated app (e.g. open file.txt)
                      history         - show input history
                      clear           - clear terminal history (internal)
                      settings|console- open settings screen
                      clearwork       - clear work folder (SAF) configured in settings (recursive)
                      ls|dir          - list files in current directory
                      cd <dir>        - change directory (supports .., relative paths)
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
                      pm list [user|system] - list installed packages (all or filtered)
                      pm install <apk>- install APK from work dir (supports paths)
                      pm uninstall <pkg> - uninstall package
                      pm launch <pkg> - launch package
                      date            - show current date/time
                      whoami          - show user info
                      uname           - show system info
                      uptime          - show system uptime
                      which <cmd>     - check if command exists
                      alias <name>=<command> - set alias
                      unalias <name>  - remove alias
                      env             - show environment vars
                      sms [number] [text] - open SMS app (with number and text)
                      call <number>   - dial number
                      email [address] [subject] [body] - open email app (with address, subject, body)
                      browser [url]   - open browser (with URL)
                      search <query>  - web search query
                      contacts        - open contacts app
                      alarm           - open alarm app
                      calc            - open calculator
                      vpns            - open VPN settings
                      btss            - open battery settings
                      wifi            - open Wi-Fi settings
                      bts             - open Bluetooth settings
                      data            - open mobile data settings
                      apm             - open airplane mode settings
                      snd             - open sound settings
                      dsp             - open display settings
                      apps            - open app settings
                      stg             - open storage settings
                      sec             - open security settings
                      loc             - open location settings
                      nfc             - open NFC settings
                      cam             - open camera
                      clk             - open date/time settings
                      notif           - open notification settings
                      acc             - open account settings
                      dev             - open developer settings
                    """.trimIndent()
                }

                "about" -> {
                    "Info: Syndes Terminal v0.13"
                }

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
                        // Это приложение
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
                        // Пробуем как файл
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

                "history" -> {
                    if (history.isEmpty()) "(no history)"
                    else history.joinToString("\n")
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

                "clearwork" -> {
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
                    val path = if (args.isEmpty()) "." else args.joinToString(" ")
                    val (newDir, _) = resolvePath(ctx, path, isDir = true) ?: return "Error: invalid path"
                    setCurrentDir(ctx, newDir)
                    "Info: changed to ${buildPath(newDir)}"
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
                    val newDir = parent.createDirectory(dirName) ?: return "Error: cannot create directory"
                    "Info: created directory $dirPath"
                }

                "touch" -> {
                    if (args.isEmpty()) return "Error: touch <path>"
                    val filePath = args[0]
                    val (parent, fileName) = resolvePath(ctx, filePath, createDirs = true) ?: return "Error: invalid path"
                    val newFile = parent.createFile("text/plain", fileName) ?: return "Error: cannot create file"
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
                    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(name) ?: return "Error: no such file '$path'"
                    val size = calculateSize(target)
                    "$size $path"
                }

                "stat" -> {
                    if (args.isEmpty()) return "Error: stat <path>"
                    val path = args[0]
                    val (parent, name) = resolvePath(ctx, path) ?: return "Error: invalid path"
                    val target = parent.findFile(name) ?: return "Error: no such file '$path'"
                    val size = target.length()
                    val type = target.type ?: "unknown"
                    val modified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(target.lastModified()))
                    "Size: $size bytes\nType: $type\nModified: $modified"
                }

                "find" -> {
                    if (args.isEmpty()) return "Error: find <name>"
                    val searchName = args.joinToString(" ").lowercase()
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val results = current.listFiles().filter { it.name?.lowercase()?.contains(searchName) == true }.joinToString("\n") { it.name ?: "?" }
                    if (results.isEmpty()) "No matches found." else results
                }

                "alias" -> {
                    if (args.isEmpty()) return "Error: alias <name>=<command>"
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
                    val url = if (args.isEmpty()) "https://" else args.joinToString(" ")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening browser"
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
                            if (args.size < 2) return "Error: pm uninstall <pkg>"
                            val pkg = args[1]
                            val intent = Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:$pkg")
                                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                            "Info: uninstalling $pkg"
                        }
                        "launch" -> {
                            if (args.size < 2) return "Error: pm launch <pkg>"
                            val pkg = args[1]
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
                    val knownCommands = listOf("help", "about", "echo", "open", "history", "clear", "settings", "console", "clearwork",
                        "ls", "dir", "cd", "pwd", "cp", "mv", "rm", "mkdir", "touch", "cat", "ln", "wc", "head", "tail", "du", "stat", "find",
                        "pm", "date", "whoami", "uname", "uptime", "which", "alias", "unalias", "env",
                        "sms", "call", "email", "browser", "search", "contacts", "alarm", "calc",
                        "vpns", "btss", "wifi", "bts", "data", "apm", "snd", "dsp", "apps", "stg", "sec", "loc", "nfc", "cam", "clk",
                        "notif", "acc", "dev")
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
                    val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                    if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Info: opening security settings"
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
                    val intent = Intent("android.settings.NOTIFICATION_SETTINGS")
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

                else -> {
                    "Unknown command: $command"
                }
            }
        } catch (t: Throwable) {
            "Error: ${t.message ?: "execution failed"}"
        }
    }

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
     */
    private fun resolvePath(ctx: Context, path: String, createDirs: Boolean = false, isDir: Boolean = false): Pair<DocumentFile, String>? {
        if (path.isEmpty()) return null
        val current = getCurrentDir(ctx) ?: return null
        val root = getRootDir(ctx) ?: return null

        val components = path.split("/").filter { it.isNotEmpty() }
        var curDir = current

        for (i in 0 until components.size - if (isDir) 0 else 1) {
            val comp = components[i]
            when (comp) {
                "." -> {} // текущая
                ".." -> {
                    val parent = curDir.parentFile
                    if (parent != null && !parent.uri.equals(root.uri)) {
                        curDir = parent
                    }
                }
                else -> {
                    val subDir = curDir.findFile(comp)
                    if (subDir != null && subDir.isDirectory) {
                        curDir = subDir
                    } else if (createDirs) {
                        val newSubDir = curDir.createDirectory(comp) ?: return null
                        curDir = newSubDir
                    } else {
                        return null
                    }
                }
            }
        }

        val last = if (components.isEmpty()) "" else components.last()
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
}
