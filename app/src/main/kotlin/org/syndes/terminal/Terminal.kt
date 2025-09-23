package org.syndes.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.AlarmClock
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
        val name = tokens[0].lowercase()
        val args = tokens.drop(1)

        return try {
            when (name) {
                "help" -> {
                    """
                    Available commands:
                      help            - show this help
                      about           - app info/version
                      echo <text>     - print text (supports > file for redirect)
                      open <app name> - launch app by visible name (e.g. open Minecraft)
                      history         - show input history
                      clear           - clear terminal history (internal)
                      settings|console- open settings screen
                      clearwork       - clear work folder (SAF) configured in settings (recursive)
                      ls|dir          - list files in current directory
                      cd <dir>        - change directory (supports .., simple names no paths)
                      pwd             - print working directory path
                      cp <src> <dst>  - copy file (simple names)
                      mv <src> <dst>  - move file (simple names)
                      rm [-r] <file>  - remove file or dir (recursive with -r)
                      mkdir <name>    - create directory
                      touch <name>    - create empty file
                      cat <file>      - display file contents
                      pm list         - list installed packages
                      pm install <apk>- install APK from work dir
                      pm uninstall <pkg> - uninstall package
                      pm launch <pkg> - launch package
                      date            - show current date/time
                      whoami          - show user info
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
                        val fileName = args[index + 1]
                        val current = getCurrentDir(ctx) ?: return "Error: work folder not set"
                        val file = current.findFile(fileName) ?: current.createFile("text/plain", fileName)
                            ?: return "Error: cannot create file"
                        val out = ctx.contentResolver.openOutputStream(file.uri, "wt")
                            ?: return "Error: cannot open file for writing"
                        out.use { it.write(text.toByteArray()) }
                        "Info: wrote to $fileName"
                    } else {
                        args.joinToString(" ")
                    }
                }

                "open" -> {
                    if (args.isEmpty()) return "Error: specify app name to open, e.g. open Minecraft"
                    val appName = args.joinToString(" ") // support spaces in name
                    val pkg = findPackageByName(ctx, appName)
                    if (pkg == null) {
                        "Error: app '$appName' not found"
                    } else {
                        // Попытка получить launch intent
                        val pm = ctx.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            // Если контекст не Activity, нужен флаг NEW_TASK
                            if (ctx !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(launchIntent)
                            "Info: launching '$appName' (package: $pkg)"
                        } else {
                            // Фоллбек: попробуем сформировать intent по пакету
                            val fallback = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                `package` = pkg
                                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            // Если ничего не найдено, предупредим
                            val resolveList = pm.queryIntentActivities(fallback, 0)
                            if (resolveList.isNotEmpty()) {
                                ctx.startActivity(fallback)
                                "Info: launching '$appName' (package: $pkg)"
                            } else {
                                "Error: cannot launch app '$appName' (no launcher activity)"
                            }
                        }
                    }
                }

                "history" -> {
                    if (history.isEmpty()) "(no history)"
                    else history.joinToString("\n")
                }

                "clear" -> {
                    // очищаем внутреннюю историю
                    history.clear()
                    // Возвращаем информационное сообщение. MainActivity может реагировать и физически очищать экран.
                    "Info: Screen cleared."
                }

                "settings", "console" -> {
                    // открываем SettingsActivity; возвращаем null, чтобы MainActivity ничего не дописывал
                    val intent = Intent(ctx, SettingsActivity::class.java)
                    // если передан не-Activity context, добавить NEW_TASK
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
                    if (args.isEmpty()) return "Error: specify directory (simple name or ..)"
                    val dirName = args.joinToString(" ") // support spaces? but split, so no, assume no spaces
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    if (dirName == "..") {
                        val parent = current.parentFile
                        if (parent != null) {
                            setCurrentDir(ctx, parent)
                            "Info: changed to parent directory"
                        } else {
                            "Info: already at root"
                        }
                    } else {
                        val subDir = current.findFile(dirName)
                        if (subDir != null && subDir.isDirectory) {
                            setCurrentDir(ctx, subDir)
                            "Info: changed to $dirName"
                        } else {
                            "Error: no such directory '$dirName'"
                        }
                    }
                }

                "pwd" -> {
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    buildPath(current)
                }

                "cp" -> {
                    if (args.size < 2) return "Error: cp <source> <dest>"
                    val sourceName = args[0]
                    val destName = args[1]
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val src = current.findFile(sourceName) ?: return "Error: no source '$sourceName'"
                    val dest = current.createFile(src.type ?: "*/*", destName) ?: return "Error: cannot create dest"
                    copyFile(ctx, src.uri, dest.uri)
                    "Info: copied $sourceName to $destName"
                }

                "mv" -> {
                    if (args.size < 2) return "Error: mv <source> <dest>"
                    val sourceName = args[0]
                    val destName = args[1]
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val src = current.findFile(sourceName) ?: return "Error: no source '$sourceName'"
                    val dest = current.createFile(src.type ?: "*/*", destName) ?: return "Error: cannot create dest"
                    copyFile(ctx, src.uri, dest.uri)
                    src.delete()
                    "Info: moved $sourceName to $destName"
                }

                "rm" -> {
                    if (args.isEmpty()) return "Error: rm [-r] <name>"
                    val hasR = args.contains("-r")
                    val targetName = args.firstOrNull { it != "-r" } ?: return "Error: specify name"
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val target = current.findFile(targetName) ?: return "Error: no such file '$targetName'"
                    if (target.isDirectory && !hasR) {
                        if (target.listFiles().isNotEmpty()) return "Error: directory not empty, use -r"
                    }
                    recursiveDelete(target)
                    "Info: deleted $targetName"
                }

                "mkdir" -> {
                    if (args.isEmpty()) return "Error: mkdir <name>"
                    val dirName = args[0]
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val newDir = current.createDirectory(dirName) ?: return "Error: cannot create directory"
                    "Info: created directory $dirName"
                }

                "touch" -> {
                    if (args.isEmpty()) return "Error: touch <name>"
                    val fileName = args[0]
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val newFile = current.createFile("text/plain", fileName) ?: return "Error: cannot create file"
                    "Info: created file $fileName"
                }

                "cat" -> {
                    if (args.isEmpty()) return "Error: cat <file>"
                    val fileName = args[0]
                    val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                    val file = current.findFile(fileName) ?: return "Error: no such file '$fileName'"
                    val input = ctx.contentResolver.openInputStream(file.uri) ?: return "Error: cannot open file"
                    input.bufferedReader().use { it.readText() }
                }

                "pm" -> {
                    if (args.isEmpty()) return "Error: pm list|install <apk>|uninstall <pkg>|launch <pkg>"
                    when (args[0].lowercase()) {
                        "list" -> {
                            val pm = ctx.packageManager
                            pm.getInstalledApplications(0).joinToString("\n") { it.packageName }
                        }
                        "install" -> {
                            if (args.size < 2) return "Error: pm install <apk>"
                            val apkName = args[1]
                            val current = getCurrentDir(ctx) ?: return "Error: work folder not set."
                            val apkFile = current.findFile(apkName) ?: return "Error: no such APK '$apkName'"
                            if (!apkName.endsWith(".apk")) return "Error: not an APK file"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(apkFile.uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                            "Info: installing $apkName"
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

                else -> {
                    "Unknown command: $command"
                }
            }
        } catch (t: Throwable) {
            // аккуратно возвращаем ошибку
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

    /**
     * Ищет packageName по видимому имени приложения (label) или по packageName частично.
     * Сначала пробует точное совпадение label (ignoreCase), затем contains по label, затем по packageName.
     * Ищет только приложения с LAUNCHER activity (чтобы можно было запустить).
     */
    private fun findPackageByName(ctx: Context, appName: String): String? {
        val pm = ctx.packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(queryIntent, 0)

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
