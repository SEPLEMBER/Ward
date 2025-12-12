package org.syndes.terminal

import android.content.Context
import android.content.Intent
import android.provider.Settings

// Простая заглушка Terminal2 для тестирования.
// Команда "vpd" возвращает "7" и открывает системные настройки клавиатуры.
class Terminal2 {

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
                "hi" -> {
                    """


             .---.
            /     \
            \.@-@./
            /`\_/`\
           //  _  \\
          | \     )|_
         /`\_`>  <_/ \
Hello!   \__/'---'\__/



                    """.trimIndent()
                }

                "about" -> {
                    "Info: Syndes Terminal v0.17.3"
                }

            else -> {
                // Не обработано — возвращаем пустую строку, чтобы TerminalDispatcher мог попробовать дальше.
                ""
            }
        }
    }
}
