package org.syndes.terminal

import android.content.Context
import android.content.Intent
import android.provider.Settings

// Заглушка Terminal3 для тестирования
class Terminal3 {

    fun execute(command: String, ctx: Context): String {
        return when (command.lowercase()) {
            "rtx" -> {
                try {
                    // Открытие настроек Wi-Fi
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) { }

                "3"   // тестовый вывод
            }

            else -> "" // не обработано — передаём дальше
        }
    }
}
