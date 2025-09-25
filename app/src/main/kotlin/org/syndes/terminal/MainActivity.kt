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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    // dynamic progress text
    private var progressTextView: TextView? = null
    private var progressJob: Job? = null

    private val terminal = Terminal() // предполагается, что Terminal.kt существует

    private val PREFS_NAME = "terminal_prefs"

    // список "тяжёлых" команд, которые нужно выполнять в IO
    private val heavyCommands = setOf(
        "rm", "cp", "mv", "replace", "encrypt", "decrypt", "cmp", "diff",
        "replace", "rename", "backup", "snapshot", "trash", "cleartrash",
        "sha256", "md5", "backup", "snapshot"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // чтобы при появлении клавиатуры не перестраивать layout полностью (уменьшает "прыжки")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        // Включаем прокрутку
        terminalOutput.movementMethod = ScrollingMovementMethod()

        // Добавляем динамический progressTextView прямо над строкой ввода (программно)
        val root = findViewById<LinearLayout>(R.id.root)
        progressTextView = TextView(this).apply {
            textSize = 12f
            setPadding(6, 6, 6, 6)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_info))
            // initially hidden
            visibility = TextView.GONE
            // use monospace to match terminal look
            typeface = terminalOutput.typeface
        }
        // add before last child (input row is last in layout xml)
        val insertIndex = (root.childCount - 1).coerceAtLeast(0)
        root.addView(progressTextView, insertIndex)

        // Вступительное сообщение (подсветка info)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        terminalOutput.append(colorize("Welcome to Syndes Terminal!\nType 'help' to see commands.\n\n", infoColor))

        // Переопределяем кнопку: текстовый вид, жёлтый цвет (вшитый)
        sendButton.text = "RUN"
        val embeddedYellow = Color.parseColor("#FFD54F")
        sendButton.setTextColor(embeddedYellow)
        sendButton.setBackgroundColor(Color.TRANSPARENT)

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

        // цвета (ресурсные)
        val inputColor = ContextCompat.getColor(this, R.color.color_command)
        val errorColor = ContextCompat.getColor(this, R.color.color_error)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        val defaultColor = ContextCompat.getColor(this, R.color.terminal_text)
        val systemYellow = Color.parseColor("#FFD54F")

        // подсветка команды
        terminalOutput.append(colorize("\n> $command\n", inputColor))

        // special-case: clear should clear screen immediately (Terminal also clears history)
        if (command.equals("clear", ignoreCase = true)) {
            // clear UI
            terminalOutput.text = ""
            // still call Terminal to keep history cleared etc
            try {
                val maybe = terminal.execute(command, this)
                // Terminal returns "Info: Screen cleared." — не обязательно показывать
                // но если он returned text other than the standard, show it:
                if (maybe != null && !maybe.startsWith("Info: Screen cleared.", ignoreCase = true)) {
                    terminalOutput.append(colorize(maybe + "\n", infoColor))
                }
            } catch (t: Throwable) {
                terminalOutput.append(colorize("Error: command execution failed\n", errorColor))
            }
            inputField.text.clear()
            // keep focus
            inputField.requestFocus()
            return
        }

        // special-case: exit
        if (command.equals("exit", ignoreCase = true)) {
            terminalOutput.append(colorize("shutting down...\n", infoColor))
            // small delay so user can see message
            lifecycleScope.launch {
                delay(300)
                finishAffinity()
            }
            return
        }

        // determine whether to run on background (IO) or main thread
        val cmdToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
        val runInIo = heavyCommands.contains(cmdToken)

        if (runInIo) {
            // show progress text and run in IO
            showProgress("Working")
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        // Terminal.execute may access UI (startActivity) for some commands; heavy list should avoid those.
                        terminal.execute(command, this@MainActivity)
                    } catch (t: Throwable) {
                        "Error: ${t.message ?: "execution failed"}"
                    }
                }
                hideProgress()
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
        } else {
            // run on main thread (fast UI commands that may start activities)
            val result: String? = try {
                terminal.execute(command, this)
            } catch (t: Throwable) {
                "Error: command execution failed"
            }
            handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
        }

        // очистка поля ввода and keep focus
        inputField.text.clear()
        inputField.requestFocus()
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
        // автопрокрутка — берём настройку
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoScroll = prefs.getBoolean("scroll_behavior", true)
        if (autoScroll) scrollToBottom()

        // small measure to reduce layout "jumping": re-focus input and ensure selection
        inputField.post {
            inputField.requestFocus()
            try {
                inputField.setSelection(0)
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun showProgress(baseText: String) {
        hideProgress() // cancel existing if any
        progressTextView?.visibility = TextView.VISIBLE
        progressTextView?.text = "$baseText..."
        // animate dots every 300ms
        progressJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                val s = buildString {
                    append(baseText)
                    append(".")
                    repeat(dots) { append(".") }
                }
                progressTextView?.text = s
                dots = (dots + 1) % 4
                delay(300)
            }
        }
    }

    private fun hideProgress() {
        progressJob?.cancel()
        progressJob = null
        progressTextView?.visibility = TextView.GONE
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

    override fun onDestroy() {
        super.onDestroy()
        hideProgress()
    }
}
