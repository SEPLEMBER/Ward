package org.syndes.terminal

import android.content.Context
import android.content.Intent
import android.provider.Settings

// Заглушка Terminal2 для теста
class Terminal2 {

    fun execute(command: String, ctx: Context): String {
        return when (command.lowercase()) {
            "vpd" -> {
                try {
                    // Открытие настроек клавиатуры
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) { }
