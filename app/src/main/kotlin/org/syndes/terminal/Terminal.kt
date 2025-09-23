package org.syndes.terminal

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
        val arg = if (parts.size > 1) parts[1] else ""

        return try {
            when (name) {
                "help" -> {
                    // краткий список доступных команд
                    """
                    Available commands:
                      help            - show this help
                      about           - app info/version
                      echo <text>     - print text
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

                "history" -> {
                    if (history.isEmpty()) "(no history)"
                    else history.joinToString("\n")
                }

                "clear" -> {
                    // очищаем внутреннюю историю
                    history.clear()
                    // Возвращаем информационное сообщение. Если нужно физически очистить TextView,
                    // MainActivity может смотреть на это сообщение и реагировать (например, если результат == "Screen cleared.").
                    "Info: Screen cleared."
                }

                "settings", "console" -> {
                    // открываем SettingsActivity; возвращаем null, чтобы MainActivity ничего не дописывал
                    val intent = Intent(ctx, SettingsActivity::class.java)
                    // если передан не-Activity context, может понадобиться флаг NEW_TASK; но обычно мы вызываем из Activity
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
}
