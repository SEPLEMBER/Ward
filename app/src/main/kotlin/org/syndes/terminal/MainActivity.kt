package org.syndes.terminal

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    private val terminal = Terminal() // предполагается, что Terminal.kt существует

    private val PREFS_NAME = "terminal_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        // Включаем прокрутку
        terminalOutput.movementMethod = ScrollingMovementMethod()

        // Вступительное сообщение (подсветка info)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        terminalOutput.append(colorize("Welcome to Syndes Terminal!\nType 'help' to see commands.\n\n", infoColor))

        // Обработчики
        sendButton.setOnClickListener { sendCommand() }

        // Enter / actionDone запускает команду
        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendCommand()
                true
            } else {
                false
            }
        }

        inputField.requestFocus()
    }

    private fun sendCommand() {
        val command = inputField.text.toString().trim()
        if (command.isEmpty()) return

        // цвета
        val inputColor = ContextCompat.getColor(this, R.color.color_command)
        val errorColor = ContextCompat.getColor(this, R.color.color_error)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        val defaultColor = ContextCompat.getColor(this, R.color.terminal_text)

        // подсветка команды
        terminalOutput.append(colorize("\n> $command\n", inputColor))

        // Выполнение команды — предполагается сигнатура execute(command, context): String?
        // Terminal может сам стартовать SettingsActivity и вернуть null (в этом случае мы ничего не добавляем).
        val result: String? = try {
            terminal.execute(command, this)
        } catch (t: Throwable) {
            // На случай, если Terminal ещё не адаптирован — показываем аккуратно
            "Error: command execution failed"
        }

        if (result != null) {
            // выбираем цвет результата по префиксу
            val resultColor = when {
                result.startsWith("Error", ignoreCase = true) -> errorColor
                result.startsWith("Info", ignoreCase = true) -> infoColor
                else -> defaultColor
            }
            terminalOutput.append(colorize(result + "\n", resultColor))
        }

        // очистка поля ввода
        inputField.text.clear()

        // автопрокрутка — берём настройку
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoScroll = prefs.getBoolean("scroll_behavior", true)
        if (autoScroll) scrollToBottom()
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

    private fun scrollToBottom() {
        terminalOutput.post {
            val layout = terminalOutput.layout ?: return@post
            val scrollAmount = layout.getLineTop(terminalOutput.lineCount) - terminalOutput.height
            if (scrollAmount > 0) terminalOutput.scrollTo(0, scrollAmount)
            else terminalOutput.scrollTo(0, 0)
        }
    }
}
