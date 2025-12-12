package org.syndes.terminal

import android.content.Context
import android.content.Intent
import android.provider.Settings

// Простая заглушка Terminal2 для тестирования.
// Команда "vpd" возвращает "7" и открывает системные настройки клавиатуры.
class Terminal2 {

    /**
     * Примитивный execute: обрабатывает только "vpd".
     * Остальные команды возвращают пустую строку "" чтобы позволить диспетчеру попробовать другие реализации.
     */
    fun execute(command: String, ctx: Context): String {
        return when (command.lowercase().trim()) {
            "vpd" -> {
                try {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    // Игнорируем ошибки при попытке запустить настройки (без крэша)
                }
                "7"
            }

            else -> {
                // Не обработано — возвращаем пустую строку, чтобы TerminalDispatcher мог попробовать дальше.
                ""
            }
        }
    }
}
