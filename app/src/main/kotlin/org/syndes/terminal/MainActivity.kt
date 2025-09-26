package org.syndes.terminal

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var progressRow: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var progressJob: Job? = null

    private val terminal = Terminal() // предполагается, что Terminal.kt существует

    private val PREFS_NAME = "terminal_prefs"

    // список "тяжёлых" команд, которые нужно выполнять в IO
    private val heavyCommands = setOf(
        "rm", "cp", "mv", "replace", "encrypt", "decrypt", "cmp", "diff",
        "rename", "backup", "snapshot", "trash", "cleartrash",
        "sha256", "md5", "delete all y"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // используем ADJUST_RESIZE — при появлении клавиатуры layout будет уменьшаться,
        // это предотвращает «уход» верхней части TextView за пределы экрана
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        progressRow = findViewById(R.id.progressRow)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)

        // Включаем прокрутку
        terminalOutput.movementMethod = ScrollingMovementMethod()

        // Вступительное сообщение (подсветка info)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        terminalOutput.append(colorize("Welcome to Syndes Terminal!\nType 'help' to see commands.\n\n", infoColor))

        // Переопределяем кнопку: текстовый вид, жёлтый цвет (вшитый)
        sendButton.text = "RUN"
        val embeddedYellow = Color.parseColor("#FFD54F")
        sendButton.setTextColor(embeddedYellow)
        sendButton.setBackgroundColor(Color.TRANSPARENT)

        // Сделаем inputField явно фокусируемым в touch-mode (предотвращает потерю ввода)
        inputField.isFocusable = true
        inputField.isFocusableInTouchMode = true

        // Обработчики
        sendButton.setOnClickListener { sendCommand() }

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

        // Если пользователь тапает по выводу — переводим фокус на поле ввода и показываем клавиатуру
        terminalOutput.setOnClickListener {
            focusAndShowKeyboard()
        }

        // По умолчанию даём фокус полю и пытаемся показать клавиатуру (параметр устройства/IME может мешать)
        inputField.post {
            inputField.requestFocus()
        }
    }

    private fun sendCommand() {
        val command = inputField.text.toString().trim()
        if (command.isEmpty()) {
            // если поле пустое - просто убедимся, что клавиатура видна и поле сфокусировано
            focusAndShowKeyboard()
            return
        }

        val inputColor = ContextCompat.getColor(this, R.color.color_command)
        val errorColor = ContextCompat.getColor(this, R.color.color_error)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        val defaultColor = ContextCompat.getColor(this, R.color.terminal_text)
        val systemYellow = Color.parseColor("#FFD54F")

        terminalOutput.append(colorize("\n> $command\n", inputColor))

        if (command.equals("clear", ignoreCase = true)) {
            terminalOutput.text = ""
            try {
                val maybe = terminal.execute(command, this)
                if (maybe != null && !maybe.startsWith("Info: Screen cleared.", ignoreCase = true)) {
                    terminalOutput.append(colorize(maybe + "\n", infoColor))
                }
            } catch (t: Throwable) {
                terminalOutput.append(colorize("Error: command execution failed\n", errorColor))
            }
            inputField.text.clear()
            focusAndShowKeyboard()
            return
        }

        if (command.equals("exit", ignoreCase = true)) {
            terminalOutput.append(colorize("shutting down...\n", infoColor))
            lifecycleScope.launch {
                delay(300)
                finishAffinity()
            }
            return
        }

        val cmdToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
        val runInIo = heavyCommands.contains(cmdToken)

        if (runInIo) {
            showProgress("Working")
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        terminal.execute(command, this@MainActivity)
                    } catch (t: Throwable) {
                        "Error: ${t.message ?: "execution failed"}"
                    }
                }
                hideProgress()
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
        } else {
            val result: String? = try {
                terminal.execute(command, this)
            } catch (t: Throwable) {
                "Error: command execution failed"
            }
            handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
        }

        inputField.text.clear()
        focusAndShowKeyboard()
    }

    private fun handleResultAndScroll(
        command: String,
        result: String?,
        defaultColor: Int,
        infoColor: Int,
        errorColor: Int,
        systemYellow: Int
    ) {
        if (result != null) {
            val firstToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
            val resultColor = when {
                result.startsWith("Error", ignoreCase = true) -> errorColor
                result.startsWith("Info", ignoreCase = true) -> infoColor
                firstToken in setOf("mem", "device", "uname", "uptime", "date") -> systemYellow
                else -> defaultColor
            }
            terminalOutput.append(colorize(result + "\n", resultColor))
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoScroll = prefs.getBoolean("scroll_behavior", true)
        if (autoScroll) scrollToBottom()

        // подстраховка: гарантируем, что поле остаётся в фокусе и клавиатура видима
        inputField.post {
            inputField.requestFocus()
        }
    }

    private fun focusAndShowKeyboard() {
        inputField.post {
            inputField.requestFocus()
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun showProgress(baseText: String) {
        progressRow.visibility = TextView.VISIBLE
        progressText.text = "$baseText..."
        // animate simple dots
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                val s = buildString {
                    append(baseText)
                    repeat(dots + 1) { append('.') }
                }
                progressText.text = s
                dots = (dots + 1) % 3
                delay(300)
            }
        }
    }

    private fun hideProgress() {
        progressJob?.cancel()
        progressJob = null
        progressRow.visibility = TextView.GONE
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
            if (scrollAmount > 0) terminalOutput.scrollTo(0, scrollAmount) else terminalOutput.scrollTo(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgress()
    }
}
