package org.syndes.terminal

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    private val terminal = Terminal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        // Включаем прокрутку
        terminalOutput.movementMethod = ScrollingMovementMethod()

        // Вступительное сообщение
        terminalOutput.text = "Welcome to Syndes Terminal!\nType 'help' to see commands.\n\n"

        sendButton.setOnClickListener {
            val command = inputField.text.toString().trim()
            if (command.isNotEmpty()) {
                val result = terminal.execute(command)

                // Цвета для подсветки
                val inputColor = ContextCompat.getColor(this, android.R.color.holo_green_light)
                val errorColor = ContextCompat.getColor(this, android.R.color.holo_red_light)
                val infoColor  = ContextCompat.getColor(this, android.R.color.holo_blue_light)
                val defaultColor = ContextCompat.getColor(this, android.R.color.white)

                // Подсветка команды
                terminalOutput.append(colorize("\n> $command\n", inputColor))

                // Подсветка результата
                val resultColor = when {
                    result.startsWith("Error", ignoreCase = true) -> errorColor
                    result.startsWith("Info", ignoreCase = true) -> infoColor
                    else -> defaultColor
                }
                terminalOutput.append(colorize(result + "\n", resultColor))

                // Очистка поля ввода
                inputField.text.clear()

                // Прокрутка вниз
                terminalOutput.post {
                    val scrollAmount = terminalOutput.layout.getLineTop(terminalOutput.lineCount) - terminalOutput.height
                    if (scrollAmount > 0) terminalOutput.scrollTo(0, scrollAmount)
                    else terminalOutput.scrollTo(0, 0)
                }
            }
        }

        // Автофокус на ввод
        inputField.requestFocus()
    }

    private fun colorize(text: String, color: Int): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(text)
        spannable.setSpan(
            ForegroundColorSpan(color),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }
}
