package org.syndes.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

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

        // разбиваем на имя команды и аргумент (всё после первого пробела)
        val parts = command.split(Regex("\\s+"), limit = 2)
        val name = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        return try {
            when (name) {
                "help" -> {
                    // краткий список доступных команд
                    """
                    Available commands:
                      help            - show this help
                      about           - app info/version
                      echo <text>     - print text
                      open <app name> - launch app by visible name (e.g. open Minecraft)
                      history         - show input history
                      clear           - clear terminal history (internal)
                      settings|console- open settings screen
                      clearwork       - clear work folder (SAF) configured in settings
                    """.trimIndent()
                }

                "about" -> {
                    "Info: Syndes Terminal v0.13"
                }

                "echo" -> {
                    arg
                }

                "open" -> {
                    if (arg.isEmpty()) return "Error: specify app name to open, e.g. open Minecraft"

                    val pkg = findPackageByName(ctx, arg)
                    if (pkg == null) {
                        "Error: app '$arg' not found"
                    } else {
                        // Попытка получить launch intent
                        val pm = ctx.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            // Если контекст не Activity, нужен флаг NEW_TASK
                            if (ctx !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(launchIntent)
                            "Info: launching '$arg' (package: $pkg)"
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
                                "Info: launching '$arg' (package: $pkg)"
                            } else {
                                "Error: cannot launch app '$arg' (no launcher activity)"
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
                    // Читаем URI рабочей папки из SharedPreferences
                    val prefs = ctx.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    val uriStr = prefs.getString("work_dir_uri", null)
                        ?: return "Error: work folder not set. Set it in settings."

                    val treeUri = try {
                        Uri.parse(uriStr)
                    } catch (e: Exception) {
                        return "Error: bad work folder URI"
                    }

                    val doc = DocumentFile.fromTreeUri(ctx, treeUri)
                        ?: return "Error: cannot access work folder (DocumentFile is null)"

                    // Перебираем файлы в корне выбранной папки и пытаемся удалить их.
                    // (Не рекурсивно — можно расширить при необходимости).
                    var deleted = 0
                    var failed = 0
                    val children = doc.listFiles()
                    for (child in children) {
                        try {
                            // пытаемся удалить только файлы; если нужно — удалять и папки (рекурсивно)
                            val success = if (child.isFile) {
                                child.delete()
                            } else {
                                // для директорий пробуем удалить только если пустая
                                if (child.listFiles().isEmpty()) child.delete() else false
                            }
                            if (success) deleted++ else failed++
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

                else -> {
                    "Unknown command: $command"
                }
            }
        } catch (t: Throwable) {
            // аккуратно возвращаем ошибку
            "Error: ${t.message ?: "execution failed"}"
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
