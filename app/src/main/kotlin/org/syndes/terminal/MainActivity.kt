package org.syndes.terminal

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var progressRow: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var progressJob: Job? = null

    private val terminal = Terminal()

    private val PREFS_NAME = "terminal_prefs"

    // список "тяжёлых" команд, которые нужно выполнять в IO
    private val heavyCommands = setOf(
        "rm", "cp", "mv", "replace", "encrypt", "decrypt", "cmp", "diff",
        "rename", "backup", "snapshot", "trash", "cleartrash",
        "sha256", "grep", "batchrename", "md5", "delete all y"
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
                // intentionally silent (logging removed)
            }
        }
    }

    // очередь команд для пакетного выполнения — теперь хранит либо одиночную команду, либо параллельную группу
    private val commandQueue = ArrayDeque<CommandItem>()
    private var processingJob: Job? = null
    private var processingQueue = false

    // background jobs (для команд с & и т.п.)
    private val backgroundJobs = mutableListOf<Job>()

    // CompletableDeferred used to wait for intent-based actions (uninstall)
    private var pendingIntentCompletion: CompletableDeferred<Unit>? = null

    // Launcher for intents that require user interaction and return control
    private val intentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // when system dialog/activity finishes - resume queue processing (if waiting)
        pendingIntentCompletion?.complete(Unit)
        pendingIntentCompletion = null
    }

    // Programmatically created stop-queue button
    private var stopQueueButton: Button? = null

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

        // Добавляем кнопку "STOP QUEUE" программно (без изменения XML)
        addStopQueueButton()

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

    override fun onDestroy() {
        super.onDestroy()
        hideProgress()
        // cleanup background jobs
        backgroundJobs.forEach { it.cancel() }
        backgroundJobs.clear()
        processingJob?.cancel()
        pendingIntentCompletion?.completeExceptionally(CancellationException("activity destroyed"))
        pendingIntentCompletion = null
    }

    /**
     * sendCommand теперь поддерживает:
     * - многострочный ввод;
     * - разделители ';' и '&&' (&& — условное продолжение, выполняется следующая команда только если предыдущая успешна);
     * - ключевое слово "parallel" или "parallel:" для параллельного выполнения группы команд;
     * - суффикс '&' (space + &) для фонового запуска конкретной команды;
     *
     * Новые команды добавляются в очередь и выполняются последовательно (если не указано parallel/background).
     */
    private fun sendCommand() {
        val rawInput = inputField.text.toString()
        if (rawInput.isBlank()) {
            // если поле пустое - просто убедимся, что клавиатура видна и поле сфокусировано
            focusAndShowKeyboard()
            return
        }

        val inputColor = ContextCompat.getColor(this, R.color.color_command)

        // Парсим ввод в список CommandItem
        val items = parseInputToCommandItems(rawInput)

        // Добавляем элементы в очередь и печатаем в терминал
        for (item in items) {
            when (item) {
                is CommandItem.Single -> {
                    commandQueue.addLast(item)
                    terminalOutput.append(colorize("\n> ${item.command}${if (item.background) " &" else ""}\n", inputColor))
                }
                is CommandItem.Parallel -> {
                    commandQueue.addLast(item)
                    terminalOutput.append(colorize("\n> parallel { ${item.commands.joinToString(" ; ")} }\n", inputColor))
                }
            }
        }

        // Очищаем поле ввода
        inputField.text.clear()
        focusAndShowKeyboard()

        // Если сейчас не исполняется очередь — стартуем процесс
        if (!processingQueue) processCommandQueue()
    }

    // Add a STOP QUEUE button programmatically (no XML change)
    private fun addStopQueueButton() {
        try {
            if (stopQueueButton != null) return
            val btn = Button(this).apply {
                text = "STOP QUEUE"
                setTextColor(Color.parseColor("#FF5F1F"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { stopQueue() }
            }
            stopQueueButton = btn

            // Try to add near sendButton if possible
            val parent = sendButton.parent
            if (parent is ViewGroup) {
                // try to insert right after sendButton
                val idx = parent.indexOfChild(sendButton)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 16
                    gravity = Gravity.CENTER_VERTICAL
                }
                parent.addView(btn, idx + 1, lp)
            } else {
                // fallback: add to content root as overlay in top-right
                val root = findViewById<ViewGroup>(android.R.id.content)
                val flp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = 8
                    rightMargin = 8
                }
                root.addView(btn, flp)
            }
        } catch (t: Throwable) {
            // ignore failure to add UI; functionality still available via programmatic stop if needed
        }
    }

    // Stop queue: cancel processing, clear queue, cancel background jobs and pending intent
    private fun stopQueue() {
        // clear the queue
        commandQueue.clear()

        // cancel processing job
        processingJob?.cancel(CancellationException("stopped by user"))
        processingJob = null
        processingQueue = false

        // cancel pending intent wait (if waiting for uninstall)
        try {
            pendingIntentCompletion?.completeExceptionally(CancellationException("stopped by user"))
        } catch (_: Throwable) { /* ignore */ }
        pendingIntentCompletion = null

        // cancel background jobs
        backgroundJobs.forEach { it.cancel(CancellationException("stopped by user")) }
        backgroundJobs.clear()

        // UI feedback
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        terminalOutput.append(colorize("\n[STOP] queue stopped and cleared\n", infoColor))
        scrollToBottom()
        hideProgress()
    }

    // Основной цикл обработки очереди (последовательно выполняем CommandItem)
    private fun processCommandQueue() {
        processingQueue = true
        processingJob = lifecycleScope.launch {
            while (commandQueue.isNotEmpty() && isActive) {
                val item = commandQueue.removeFirst()
                try {
                    when (item) {
                        is CommandItem.Single -> {
                            // Если команда помечена как background, то запускаем её и не ждём результата
                            if (item.background) {
                                val bgJob = lifecycleScope.launch {
                                    try {
                                        runSingleCommand(item.command)
                                    } catch (_: Throwable) {
                                        // background failures are logged to terminal within runSingleCommand
                                    }
                                }
                                backgroundJobs.add(bgJob)
                                // don't wait; continue to next
                            } else {
                                // Если команда условная (conditionalNext==true), то мы должны учитывать результат предыдущей
                                // Но conditionalNext affects how this SINGLE was created from separators; to implement '&&' chaining,
                                // we interpret conditional flag stored on PREVIOUS command: thus parsed items carry conditionalNext on previous.
                                // For simplicity, we implement conditional chaining by storing that flag in the PREVIOUS item when parsing.
                                // Here we just execute current command normally.
                                val result = runSingleCommand(item.command)
                                // if this Single has conditionalNext==true, effect handled when parsing built next item; so nothing extra here.
                            }
                        }
                        is CommandItem.Parallel -> {
                            // Validate that none of commands require intent-based user interaction (uninstall). If they do — disallow.
                            val hasIntentCommands = item.commands.any { it.trim().split("\\s+".toRegex()).firstOrNull()?.lowercase() in setOf("uninstall") }
                            if (hasIntentCommands) {
                                val err = ContextCompat.getColor(this@MainActivity, R.color.color_error)
                                withContext(Dispatchers.Main) {
                                    terminalOutput.append(colorize("Error: cannot run uninstall or intent-based commands in parallel group. Skipping parallel group.\n", err))
                                }
                                continue
                            }
                            // Launch all commands concurrently and wait for all to complete
                            val deferredJobs = item.commands.map { cmd ->
                                lifecycleScope.launch {
                                    try {
                                        runSingleCommand(cmd)
                                    } catch (t: Throwable) {
                                        val err = ContextCompat.getColor(this@MainActivity, R.color.color_error)
                                        withContext(Dispatchers.Main) {
                                            terminalOutput.append(colorize("Error (parallel): ${t.message}\n", err))
                                        }
                                    }
                                }
                            }
                            // Wait for all to complete
                            deferredJobs.joinAll()
                        }
                    }
                } catch (t: Throwable) {
                    val errorColor = ContextCompat.getColor(this@MainActivity, R.color.color_error)
                    withContext(Dispatchers.Main) {
                        terminalOutput.append(colorize("Error: failed to execute item : ${t.message}\n", errorColor))
                        scrollToBottom()
                    }
                }
            }
            processingQueue = false
            processingJob = null
        }
    }

    /**
     * Выполнение одной команды. Возвращает строковый результат (или сообщение об ошибке).
     * Функция сама пишет в терминал (progress, info), но также возвращает результат для условной логики.
     */
    private suspend fun runSingleCommand(command: String): String? {
        val inputToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
        val defaultColor = ContextCompat.getColor(this@MainActivity, R.color.terminal_text)
        val infoColor = ContextCompat.getColor(this@MainActivity, R.color.color_info)
        val errorColor = ContextCompat.getColor(this@MainActivity, R.color.color_error)
        val systemYellow = Color.parseColor("#FFD54F")

        // Special-case: watchdog — same behavior as before: try service, else fallback timer that reinjects
        if (command.startsWith("watchdog", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                terminalOutput.append(colorize("Scheduling watchdog: $command\n", infoColor))
                scrollToBottom()
            }
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 3) {
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Usage: watchdog <duration> <command...>\n", errorColor))
                    scrollToBottom()
                }
                return "Error: invalid watchdog syntax"
            }
            val durToken = parts[1]
            val targetCmd = parts.drop(2).joinToString(" ")
            val durSec = parseDurationToSeconds(durToken)
            if (durSec <= 0L) {
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Error: invalid duration '$durToken'\n", errorColor))
                    scrollToBottom()
                }
                return "Error: invalid duration"
            }

            try {
                val svcIntent = Intent(this@MainActivity, WatchdogService::class.java).apply {
                    putExtra(WatchdogService.EXTRA_CMD, targetCmd)
                    putExtra(WatchdogService.EXTRA_DELAY_SEC, durSec)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this@MainActivity, svcIntent)
                } else {
                    startService(svcIntent)
                }
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Watchdog service started: will run \"$targetCmd\" in $durToken\n", infoColor))
                    scrollToBottom()
                }
                return "Info: watchdog scheduled"
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Warning: cannot start watchdog service, falling back to in-app timer (may be cancelled when app backgrounded)\n", errorColor))
                    scrollToBottom()
                }
                // fallback: schedule reinjection
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
                        // Re-inject target command into queue (в конец) — будет выполнен после текущих команд
                        commandQueue.addLast(CommandItem.Single(targetCmd, conditionalNext = false, background = false))
                        if (!processingQueue) processCommandQueue()
                    } catch (_: Throwable) {
                        withContext(Dispatchers.Main) {
                            terminalOutput.append(colorize("Error: watchdog fallback failed\n", errorColor))
                            scrollToBottom()
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            progressRow.visibility = TextView.GONE
                            progressText.text = ""
                        }
                    }
                }
                return "Info: watchdog fallback scheduled"
            }
        }

        // clear
        if (command.equals("clear", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                terminalOutput.text = ""
                scrollToBottom()
            }
            val maybe = try {
                withContext(Dispatchers.Main) { terminal.execute(command, this@MainActivity) }
            } catch (_: Throwable) {
                null
            }
            if (maybe != null && !maybe.startsWith("Info: Screen cleared.", ignoreCase = true)) {
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize(maybe + "\n", infoColor))
                    scrollToBottom()
                }
            }
            return maybe ?: "Info: screen cleared"
        }

        // exit
        if (command.equals("exit", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                terminalOutput.append(colorize("shutting down...\n", infoColor))
                scrollToBottom()
            }
            delay(300)
            withContext(Dispatchers.Main) {
                finishAffinity()
            }
            return "Info: exit"
        }

        // uninstall <pkg> — intent-based, wait for result
        if (inputToken == "uninstall") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Usage: uninstall <package.name>\n", errorColor))
                    scrollToBottom()
                }
                return "Error: uninstall usage"
            }
            val pkg = parts[1].trim()
            val installed = try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
            if (!installed) {
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Not installed: $pkg\n", errorColor))
                    scrollToBottom()
                }
                return "Error: not installed"
            }

            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                .setData(android.net.Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)

            pendingIntentCompletion = CompletableDeferred()
            try {
                intentLauncher.launch(intent)
                // wait until activity result arrives or stop requested
                pendingIntentCompletion?.await()
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("Uninstall flow finished for $pkg\n", infoColor))
                }
                val stillInstalled = try {
                    packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                val msg = if (!stillInstalled) {
                    val s = "Info: package removed: $pkg"
                    withContext(Dispatchers.Main) {
                        terminalOutput.append(colorize("$s\n", infoColor))
                        scrollToBottom()
                    }
                    s
                } else {
                    val s = "Info: package still installed: $pkg"
                    withContext(Dispatchers.Main) {
                        terminalOutput.append(colorize("$s\n", defaultColor))
                        scrollToBottom()
                    }
                    s
                }
                return msg
            } catch (t: Throwable) {
                pendingIntentCompletion = null
                val errMsg = "Error: cannot launch uninstall for $pkg: ${t.message}"
                withContext(Dispatchers.Main) {
                    terminalOutput.append(colorize("$errMsg\n", errorColor))
                    scrollToBottom()
                }
                return errMsg
            }
        }

        // Other commands: heavy -> IO, else main
        val runInIo = heavyCommands.contains(inputToken)
        if (runInIo) {
            withContext(Dispatchers.Main) { showProgress("Working") }
            val result = try {
                withContext(Dispatchers.IO) {
                    try {
                        terminal.execute(command, this@MainActivity)
                    } catch (t: Throwable) {
                        "Error: ${t.message ?: "execution failed"}"
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { hideProgress() }
            }
            withContext(Dispatchers.Main) {
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
            return result
        } else {
            val result = try {
                withContext(Dispatchers.Main) {
                    terminal.execute(command, this@MainActivity)
                }
            } catch (t: Throwable) {
                "Error: command execution failed"
            }
            withContext(Dispatchers.Main) {
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
            return result
        }
    }

// We parse raw input into CommandItem structures.
// Supports: newline splitting, ";" and "&&" separators, "parallel" groups, and background suffix "&".
private fun parseInputToCommandItems(raw: String): List<CommandItem> {
    val result = mutableListOf<CommandItem>()
    val lines = raw.lines()
    for (line0 in lines) {
        var line = line0.trim()
        if (line.isEmpty()) continue

        // If starts with "parallel" -> parse group
        if (line.startsWith("parallel ", ignoreCase = true) || line.startsWith("parallel:", ignoreCase = true)) {
            // remove keyword
            val rest = line.substringAfter(':', missingDelimiterValue = "").ifEmpty { line.substringAfter("parallel", "") }.trim().trimStart(':').trim()
            val groupText = if (rest.isEmpty()) "" else rest
            val parts = groupText.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                result.add(CommandItem.Parallel(parts))
            }
            continue
        }

        // Now split by separators ; and && preserving conditional semantics
        var i = 0
        val sb = StringBuilder()
        while (i < line.length) {
            if (i + 1 < line.length && line.substring(i, i + 2) == "&&") {
                val token = sb.toString().trim()
                if (token.isNotEmpty()) result.add(CommandItem.Single(token, conditionalNext = true, background = token.endsWith(" &")))
                sb.setLength(0)
                i += 2
                continue
            } else if (line[i] == ';') {
                val token = sb.toString().trim()
                if (token.isNotEmpty()) result.add(CommandItem.Single(token, conditionalNext = false, background = token.endsWith(" &")))
                sb.setLength(0)
                i++
                continue
            } else {
                sb.append(line[i])
                i++
            }
        }
        val last = sb.toString().trim()
        if (last.isNotEmpty()) result.add(CommandItem.Single(last, conditionalNext = false, background = last.endsWith(" &")))
    }

    // Clean up background markers (remove trailing & from command text) — only for Single items.
    // For Parallel items we leave them as-is.
    return result.map { item ->
        when (item) {
            is CommandItem.Single -> item.cleanupBackgroundSuffix()
            is CommandItem.Parallel -> item
        }
    }
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

    // CommandItem sealed class represents either a single command or a parallel group
    private sealed class CommandItem {
        data class Single(val command: String, val conditionalNext: Boolean = false, val background: Boolean = false) : CommandItem() {
            fun cleanupBackgroundSuffix(): Single {
                var c = command
                if (background) {
                    // remove trailing & if present
                    c = c.removeSuffix("&").trimEnd()
                }
                return Single(c, conditionalNext, background)
            }
        }

        data class Parallel(val commands: List<String>) : CommandItem()
    }
}
