package org.syndes.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
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

    // receiver to show watchdog results when service finishes
    private val watchdogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val cmd = intent?.getStringExtra("cmd") ?: return
                val result = intent.getStringExtra("result") ?: ""
                runOnUiThread {
                    terminalOutput.append(colorize("\n[watchdog:$cmd] $result\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)))
                    scrollToBottom()
                }
            } catch (t: Throwable) {
                Log.w("MainActivity", "watchdogReceiver failed: ${t.message}")
            }
        }
    }

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

    override fun onResume() {
        super.onResume()
        // register receiver for watchdog results (service broadcasts)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    watchdogReceiver,
                    IntentFilter("org.syndes.terminal.WATCHDOG_RESULT"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(watchdogReceiver, IntentFilter("org.syndes.terminal.WATCHDOG_RESULT"))
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(watchdogReceiver)
        } catch (_: Exception) { /* ignore */ }
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

        // ---------------------------
        // watchdog: schedule a command to be executed after delay
        // Usage: watchdog 15s <command...>   or watchdog 5m <command...> or watchdog 2h <command...>
        // Implementation: prefer starting a Foreground Service (WatchdogService).
        // Fallback: if service start fails, run a local coroutine (best-effort, may be cancelled when activity backgrounded).
        // ---------------------------
        if (command.startsWith("watchdog", ignoreCase = true)) {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 3) {
                terminalOutput.append(colorize("Usage: watchdog <duration> <command...> e.g. watchdog 5m replace old new logs\n", errorColor))
                inputField.text.clear()
                focusAndShowKeyboard()
                return
            }

            val durToken = parts[1]
            val targetCmd = parts.drop(2).joinToString(" ")
            val durSec = parseDurationToSeconds(durToken)
            if (durSec <= 0L) {
                terminalOutput.append(colorize("Error: invalid duration '$durToken'\n", errorColor))
                inputField.text.clear()
                focusAndShowKeyboard()
                return
            }

            // Try to start WatchdogService (foreground). If service class not present or fails, fallback to in-activity coroutine.
            try {
                val svcIntent = Intent(this, WatchdogService::class.java).apply {
                    putExtra(WatchdogService.EXTRA_CMD, targetCmd)
                    putExtra(WatchdogService.EXTRA_DELAY_SEC, durSec)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, svcIntent)
                } else {
                    startService(svcIntent)
                }
                terminalOutput.append(colorize("Watchdog service started: will run \"$targetCmd\" in $durToken\n", infoColor))
            } catch (t: Throwable) {
                // fallback: local coroutine (best-effort)
                terminalOutput.append(colorize("Warning: cannot start watchdog service, falling back to in-app timer (may be cancelled when app backgrounded)\n", errorColor))
                lifecycleScope.launch {
                    try {
                        progressRow.visibility = TextView.VISIBLE
                        progressBar.isIndeterminate = true
                        var remaining = durSec

                        fun pretty(sec: Long): String {
                            val h = sec / 3600
                            val m = (sec % 3600) / 60
                            val s = sec % 60
                            return if (h > 0) String.format("%dh %02dm %02ds", h, m, s)
                            else if (m > 0) String.format("%02dm %02ds", m, s)
                            else String.format("%02ds", s)
                        }

                        progressText.text = "Watchdog: will run \"$targetCmd\" in ${pretty(remaining)}"

                        while (remaining > 0 && isActive) {
                            if (remaining >= 60L) {
                                var slept = 0L
                                while (slept < 60_000L && isActive) {
                                    delay(1000L)
                                    slept += 1000L
                                }
                                remaining -= 60L
                                if (remaining < 0L) remaining = 0L
                                progressText.text = "Watchdog: will run \"$targetCmd\" in ${pretty(remaining)}"
                            } else {
                                while (remaining > 0 && isActive) {
                                    delay(1000L)
                                    remaining -= 1L
                                    if (remaining < 0L) remaining = 0L
                                    progressText.text = "Watchdog: will run \"$targetCmd\" in ${pretty(remaining)}"
                                }
                            }
                        }

                        progressText.text = "Watchdog: executing \"$targetCmd\" now..."
                        delay(250)

                        withContext(Dispatchers.Main) {
                            inputField.setText(targetCmd)
                            sendCommand()
                        }
                    } catch (t2: Throwable) {
                        terminalOutput.append(colorize("Error: watchdog failed: ${t2.message}\n", errorColor))
                    } finally {
                        progressRow.visibility = TextView.GONE
                        progressText.text = ""
                    }
                }
            }

            // acknowledge scheduling in UI and clear input
            inputField.text.clear()
            focusAndShowKeyboard()
            return
        }

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
                handleResultAndScroll(
                    command,
                    result,
                    defaultColor = ContextCompat.getColor(this@MainActivity, R.color.terminal_text),
                    infoColor = ContextCompat.getColor(this@MainActivity, R.color.color_info),
                    errorColor = ContextCompat.getColor(this@MainActivity, R.color.color_error),
                    systemYellow = Color.parseColor("#FFD54F")
                )
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

    // Parse duration token like "15s", "5m", "2h" or plain number (seconds).
    private fun parseDurationToSeconds(tok: String): Long {
        if (tok.isEmpty()) return 0L
        val lower = tok.lowercase().trim()
        return try {
            when {
                lower.endsWith("s") && lower.length > 1 -> lower.dropLast(1).toLongOrNull() ?: 0L
                lower.endsWith("m") && lower.length > 1 -> (lower.dropLast(1).toLongOrNull() ?: 0L) * 60L
                lower.endsWith("h") && lower.length > 1 -> (lower.dropLast(1).toLongOrNull() ?: 0L) * 3600L
                else -> lower.toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) { 0L }
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
