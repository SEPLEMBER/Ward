package org.syndes.terminal

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
import androidx.documentfile.provider.DocumentFile
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

class SyPLComActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var progressRow: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var progressJob: Job? = null

    private val terminal = Terminal()

    private val PREFS_NAME = "terminal_prefs"

    // For controlling glow specifically on the terminal output
    private var terminalGlowEnabled = true
    private var terminalGlowColor = Color.parseColor("#00FFF7")
    private var terminalGlowRadius = 6f

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
                // use appendToTerminal which handles glow disabling for errors
                val infoColor = ContextCompat.getColor(this@SyPLComActivity, R.color.color_info)
                appendToTerminal(colorize("\n[watchdog:$cmd] $result\n", infoColor), infoColor)
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

    // History / state for if-chain handling and cycles
    private var lastExecutedCommand: String? = null
    private var lastResult: String? = null
    private var ifChainActive: Boolean = false
    private var ifChainFired: Boolean = false

    // Counters for "cycle next" behavior
    private var processedCommandsCount: Long = 0L

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
        appendToTerminal(colorize("Welcome to SyPL Compiler!\nType 'help' to see commands.\n\n", infoColor), infoColor)

        // Переопределяем кнопку: текстовый вид, жёлтый цвет (вшитый)
        sendButton.text = "RUN"
        val embeddedYellow = Color.parseColor("#FFD54F")
        sendButton.setTextColor(embeddedYellow)
        sendButton.setBackgroundColor(Color.TRANSPARENT)

        // --- Apply subtle neon/glow effects (safe presets) ---
        // terminal output: cyan-ish glow (subtle) - save values for later toggling
        terminalGlowColor = Color.parseColor("#00FFF7")
        terminalGlowRadius = 6f
        terminalGlowEnabled = true
        applyNeon(terminalOutput, terminalGlowColor, radius = terminalGlowRadius)

        // progress text: warm yellow glow
        applyNeon(progressText, embeddedYellow, radius = 5f)

        // send button: keep its yellow text and add small glow
        applyNeon(sendButton, embeddedYellow, radius = 6f)

        // input field: small subtle greenish glow so caret/text pop
        val subtleGreen = Color.parseColor("#39FF14")
        applyNeon(inputField, subtleGreen, radius = 3f)

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
     * Новые команды добавляются в очеред и выполняются последовательно (если не указано parallel/background).
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

        // Добавляем элементы в очеред и печатаем в терминал
        for (item in items) {
            when (item) {
                is CommandItem.Single -> {
                    commandQueue.addLast(item)
                    appendToTerminal(colorize("\n> ${item.command}${if (item.background) " &" else ""}\n", inputColor), inputColor)
                }
                is CommandItem.Parallel -> {
                    commandQueue.addLast(item)
                    appendToTerminal(colorize("\n> parallel { ${item.commands.joinToString(" ; ")} }\n", inputColor), inputColor)
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
                visibility = View.GONE // hidden by default
            }
            // add neon glow to the created button (safe preset)
            applyNeon(btn, Color.parseColor("#FF5F1F"), radius = 6f)

            stopQueueButton = btn

            // Try to add before sendButton so STOP is left, RUN is right
            val parent = sendButton.parent
            if (parent is ViewGroup) {
                val idx = parent.indexOfChild(sendButton)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 16
                }
                parent.addView(btn, idx, lp) // insert before sendButton
            } else {
                // fallback: add to content root as overlay in top-left
                val root = findViewById<ViewGroup>(android.R.id.content)
                val flp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    topMargin = 8
                    leftMargin = 8
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
        appendToTerminal(colorize("\n[STOP] queue stopped and cleared\n", infoColor), infoColor)
        scrollToBottom()
        hideProgress()
        stopQueueButton?.visibility = View.GONE
    }

    // Основной цикл обработки очереди (последовательно выполняем CommandItem)
    private fun processCommandQueue() {
        processingQueue = true
        // show stop button
        runOnUiThread { stopQueueButton?.visibility = View.VISIBLE }

        processingJob = lifecycleScope.launch {
            while (commandQueue.isNotEmpty() && isActive) {
                val item = commandQueue.removeFirst()
                try {
                    when (item) {
                        is CommandItem.Single -> {
                            // If background, start and don't wait
                            if (item.background) {
                                val bgJob = lifecycleScope.launch {
                                    try {
                                        runSingleCommand(item.command)
                                    } catch (_: Throwable) {
                                        // background failures are logged to terminal within runSingleCommand
                                    }
                                }
                                backgroundJobs.add(bgJob)
                                // don't wait; count background start as "a processed command" for cycle next semantics
                                processedCommandsCount++
                            } else {
                                // normal execution
                                runSingleCommand(item.command)
                                processedCommandsCount++
                            }
                        }
                        is CommandItem.Parallel -> {
                            // Validate that none of commands require intent-based user interaction (uninstall). If they do — disallow.
                            val hasIntentCommands = item.commands.any { it.trim().split("\\s+".toRegex()).firstOrNull()?.lowercase() in setOf("uninstall") }
                            if (hasIntentCommands) {
                                val err = ContextCompat.getColor(this@SyPLComActivity, R.color.color_error)
                                withContext(Dispatchers.Main) {
                                    appendToTerminal(colorize("Error: cannot run uninstall or intent-based commands in parallel group. Skipping parallel group.\n", err), err)
                                }
                                continue
                            }
                            // Launch all commands concurrently and wait for all to complete
                            val deferredJobs = item.commands.map { cmd ->
                                lifecycleScope.launch {
                                    try {
                                        runSingleCommand(cmd)
                                    } catch (t: Throwable) {
                                        val err = ContextCompat.getColor(this@SyPLComActivity, R.color.color_error)
                                        withContext(Dispatchers.Main) {
                                            appendToTerminal(colorize("Error (parallel): ${t.message}\n", err), err)
                                        }
                                    }
                                }
                            }
                            // Wait for all to complete
                            deferredJobs.joinAll()
                            // Count each command in parallel group as processed
                            processedCommandsCount += item.commands.size
                        }
                    }
                } catch (t: Throwable) {
                    val errorColor = ContextCompat.getColor(this@SyPLComActivity, R.color.color_error)
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: failed to execute item : ${t.message}\n", errorColor), errorColor)
                    }
                }
            }
            processingQueue = false
            processingJob = null
            // hide stop button when done
            withContext(Dispatchers.Main) {
                stopQueueButton?.visibility = View.GONE
            }
        }
    }

    /**
     * Выполнение одной команды. Возвращает строковый результат (или сообщение об ошибке).
     * Функция сама пишет в терминал (progress, info), но также возвращает результат для условной логики.
     */
    private suspend fun runSingleCommand(command: String): String? {
        val inputToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
        val defaultColor = ContextCompat.getColor(this@SyPLComActivity, R.color.terminal_text)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        val errorColor = ContextCompat.getColor(this, R.color.color_error)
        val systemYellow = Color.parseColor("#FFD54F")

        // ===== IF/ELSE handling =====
        // Syntax: if <left> = <right> then <cmd>
        // else <cmd>
        // Chain semantics: if several if-s are executed consecutively (no other non-if/else between them),
        // then an else that follows applies to the whole chain if none of the ifs fired.
        if (inputToken == "if") {
            // parse pattern: if <left> = <right> then <cmd>
            val regex = Regex("^if\\s+(.+?)\\s*=\\s*(.+?)\\s+then\\s+(.+)$", RegexOption.IGNORE_CASE)
            val m = regex.find(command)
            if (m == null) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: if <left> = <right> then <command>\n", errorColor), errorColor)
                }
                // Activate if-chain but mark not fired (invalid if -> no fire)
                ifChainActive = true
                // do not change lastExecutedCommand here
                lastExecutedCommand = command
                lastResult = "Error: if parse"
                return lastResult
            }
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            val thenCmd = m.groupValues[3].trim()

            // Evaluate condition: compare lastExecutedCommand or lastResult with left/right pairs.
            // We'll consider condition true if either lastExecutedCommand equals right or lastResult equals right.
            val cond = (lastExecutedCommand?.trim() == left) || (lastResult?.trim() == left) || (left.equals(right, ignoreCase = true))
            // Mark chain active; update fired flag if condition true
            ifChainActive = true
            if (cond) {
                ifChainFired = true
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("[if] condition true — executing: $thenCmd\n", infoColor), infoColor)
                }
                // Execute then command as if user sent it (synchronously)
                val res = try {
                    runSingleCommand(thenCmd)
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: if->then execution failed: ${t.message}\n", errorColor), errorColor)
                    }
                    "Error: if->then execution"
                }
                // record history
                lastExecutedCommand = thenCmd
                lastResult = res
                return res
            } else {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("[if] condition false — skipping\n", infoColor), infoColor)
                }
                // do not reset chain — else might follow
                // record this if check as lastExecutedCommand for traceability
                lastExecutedCommand = command
                lastResult = "Info: if skipped"
                return lastResult
            }
        }

        if (inputToken == "else") {
            // Syntax: else <cmd>
            val elseCmd = command.substringAfter("else", "").trim()
            if (!ifChainActive) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: else without preceding if-chain\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: else without if"
                // reset chain anyway
                ifChainActive = false
                ifChainFired = false
                return lastResult
            }
            // if chain active and nothing has fired — execute else
            if (!ifChainFired) {
                if (elseCmd.isBlank()) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: else requires a command: else <command>\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: else usage"
                } else {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("[else] executing: $elseCmd\n", infoColor), infoColor)
                    }
                    val res = try {
                        runSingleCommand(elseCmd)
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) {
                            appendToTerminal(colorize("Error: else execution failed: ${t.message}\n", errorColor), errorColor)
                        }
                        "Error: else execution failed"
                    }
                    lastExecutedCommand = elseCmd
                    lastResult = res
                    // reset chain
                    ifChainActive = false
                    ifChainFired = false
                    return res
                }
            } else {
                // some if already fired, ignore else
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("[else] skipped because prior if fired\n", infoColor), infoColor)
                }
                lastExecutedCommand = command
                lastResult = "Info: else skipped"
                // reset chain
                ifChainActive = false
                ifChainFired = false
                return lastResult
            }
        }

        // If we reach a non-if/else command, any existing if-chain is terminated (unless else still coming immediately in queue)
        if (ifChainActive) {
            // If the new command is not an 'else' (we handled else above), then chain is broken.
            ifChainActive = false
            ifChainFired = false
        }

        // ==== NEW: sleep command ====
        if (inputToken == "sleep") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: sleep <duration>. Examples: sleep 5s | sleep 200ms | sleep 2m\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: sleep usage"
                return lastResult
            }
            val durTok = parts[1]
            val millis = parseDurationToMillis(durTok)
            if (millis <= 0L) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: invalid duration '$durTok'\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: invalid duration"
                return lastResult
            }
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Sleeping ${millis}ms...\n", systemYellow), systemYellow)
            }
            // suspend without blocking UI
            var remaining = millis
            val chunk = 500L
            while (remaining > 0 && coroutineContext.isActive) {
                val to = if (remaining > chunk) chunk else remaining
                delay(to)
                remaining -= to
            }
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Done sleep ${durTok}\n", infoColor), infoColor)
            }
            lastExecutedCommand = command
            lastResult = "Info: slept ${durTok}"
            return lastResult
        }

        // ==== NEW: runsyd command (reads script file from SAF root /scripts and injects into inputField) ====
        if (inputToken == "runsyd") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: runsyd <name>  (looks for name.syd in scripts folder)\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: runsyd usage"
                return lastResult
            }
            val name = parts[1].trim()
            // read SAF root URI from prefs - prefer 'work_dir_uri', fallback to 'current_dir_uri'
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val safRoot = prefs.getString("work_dir_uri", null) ?: prefs.getString("current_dir_uri", null)
            if (safRoot.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: SAF root not configured. Set scripts folder in settings (work_dir_uri/current_dir_uri).\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: saf not configured"
                return lastResult
            }
            try {
                val tree = DocumentFile.fromTreeUri(this, Uri.parse(safRoot))
                if (tree == null || !tree.isDirectory) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cannot access SAF root (invalid URI)\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: saf root invalid"
                    return lastResult
                }
                val scriptsDir = tree.findFile("scripts")?.takeIf { it.isDirectory }
                if (scriptsDir == null) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: 'scripts' folder not found under SAF root\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: scripts folder missing"
                    return lastResult
                }

                // candidate filenames
                val candidates = if (name.contains('.')) {
                    listOf(name)
                } else {
                    listOf("$name.syd", "$name.sh", "$name.txt")
                }

                var found: DocumentFile? = null
                for (c in candidates) {
                    val f = scriptsDir.findFile(c)
                    if (f != null && f.isFile) {
                        found = f
                        break
                    }
                }

                if (found == null) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: script not found: tried ${candidates.joinToString(", ")}\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: script not found"
                    return lastResult
                }

                // Read file text
                val uri = found.uri
                val sb = StringBuilder()
                contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            sb.append(line).append('\n')
                        }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cannot open script file\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: cannot open"
                    return lastResult
                }

                val content = sb.toString().trimEnd()

                // inject into input field and run as if pasted
                withContext(Dispatchers.Main) {
                    inputField.setText(content)
                    inputField.setSelection(inputField.text.length)
                    appendToTerminal(colorize("Loaded script '${found.name}' — injecting commands...\n", infoColor), infoColor)
                    // call sendCommand to enqueue commands from file (this will add commands while we're processing)
                    sendCommand()
                }
                lastExecutedCommand = command
                lastResult = "Info: runsyd loaded ${found.name}"
                return lastResult
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: failed to read script: ${t.message}\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: runsyd failed"
                return lastResult
            }
        }

        // ==== NEW: random {cmd1-cmd2-cmd3} command ====
        if (inputToken == "random") {
            // syntax: random {cmd1-cmd2-cmd3}
            val afterBrace = command.substringAfter('{', "").substringBefore('}', "")
            if (afterBrace.isBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: random {cmd1-cmd2-cmd3}. Example: random {echo hi - sleep 1s - date}\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: random usage"
                return lastResult
            }
            // split by '-' and trim options
            val options = afterBrace.split('-').map { it.trim() }.filter { it.isNotEmpty() }
            if (options.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: no options found inside {}\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: random no options"
                return lastResult
            }
            // choose one randomly
            val idx = kotlin.random.Random.nextInt(options.size)
            val chosen = options[idx]
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Random chose: \"$chosen\"\n", infoColor), infoColor)
            }
            // execute chosen command and return its result
            val res = try {
                runSingleCommand(chosen)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: failed to run chosen command: ${t.message}\n", errorColor), errorColor)
                }
                "Error: random execution failed"
            }
            lastExecutedCommand = chosen
            lastResult = res
            return res
        }

        // ==== NEW: button (echo: Question - opt1=cmd1 - opt2=cmd2 - ...) ====
        if (inputToken == "button") {
            // extract text inside parentheses
            val inside = command.substringAfter('(', "").substringBefore(')', "").trim()
            if (inside.isBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: button (echo: Your question - Option1=cmd1 - Option2=cmd2 - ...)\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: button usage"
                return lastResult
            }

            // split by '-' delimiter: first part is question (may start with 'echo:')
            val parts = inside.split('-').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: button: no parts found\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: button parse"
                return lastResult
            }

            var question = parts[0]
            if (question.lowercase().startsWith("echo:")) {
                question = question.substringAfter(":", "").trim()
            }

            // parse options: label=command
            val opts = parts.drop(1).mapNotNull { p ->
                val eq = p.indexOf('=')
                if (eq <= 0) {
                    // If no '=', treat the whole token as both label and command
                    val lab = p
                    val cmd = p
                    lab to cmd
                } else {
                    val lab = p.substring(0, eq).trim()
                    val cmd = p.substring(eq + 1).trim()
                    if (lab.isEmpty() || cmd.isEmpty()) null else lab to cmd
                }
            }

            if (opts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: button: no options provided (use Option=cmd)\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: button no options"
                return lastResult
            }

            val selection = CompletableDeferred<String?>()
            var overlayView: View? = null

            // create modal overlay with question and buttons (on main thread)
            withContext(Dispatchers.Main) {
                try {
                    val root = findViewById<ViewGroup>(android.R.id.content)
                    val overlay = FrameLayout(this@SyPLComActivity).apply {
                        // semi-transparent matte dark overlay: alpha + #0A0A0A
                        setBackgroundColor(Color.parseColor("#800A0A0A")) // alpha 0x80 + #0A0A0A
                        isClickable = true // consume touches
                    }

                    val container = LinearLayout(this@SyPLComActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        val pad = (16 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        val lp = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                        layoutParams = lp
                        // matte dark panel background for button area
                        setBackgroundColor(Color.parseColor("#0A0A0A"))
                    }

                    val tv = TextView(this@SyPLComActivity).apply {
                        text = question
                        setTextColor(ContextCompat.getColor(this@SyPLComActivity, R.color.color_info)) // keep message color
                        setTextIsSelectable(false)
                        val padv = (8 * resources.displayMetrics.density).toInt()
                        setPadding(padv, padv, padv, padv)
                    }
                    container.addView(tv)

                    // buttons column (vertical) — supports many options
                    val btnCol = LinearLayout(this@SyPLComActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    for ((label, cmd) in opts) {
                        val b = Button(this@SyPLComActivity).apply {
                            text = label
                            isAllCaps = false
                            setTextColor(Color.WHITE) // make button text white
                            setBackgroundColor(Color.TRANSPARENT)
                            setOnClickListener {
                                // complete with command
                                try {
                                    selection.complete(cmd)
                                } catch (_: Throwable) { /* ignore */ }
                                // remove overlay
                                try { root.removeView(overlay) } catch (_: Throwable) { }
                            }
                        }
                        // keep neon on buttons if desired (does not affect terminal glow)
                        try { applyNeon(b, Color.parseColor("#00FFF7"), radius = 4f) } catch (_: Throwable) {}
                        val blp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (6 * resources.displayMetrics.density).toInt()
                        }
                        btnCol.addView(b, blp)
                    }
                    container.addView(btnCol)

                    overlay.addView(container)
                    root.addView(overlay, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))

                    overlayView = overlay

                    // Also print question into terminal so user sees context in output
                    appendToTerminal(colorize("\n[button] $question\n", infoColor), infoColor)
                    appendToTerminal(colorize("[button] choose one of: ${opts.map { it.first }.joinToString(", ")}\n", infoColor), infoColor)
                } catch (t: Throwable) {
                    // UI creation failed
                    appendToTerminal(colorize("Error: cannot show button UI: ${t.message}\n", errorColor), errorColor)
                    selection.complete(null)
                }
            }

            // wait for selection or cancellation
            val chosenCmd: String? = try {
                selection.await()
            } catch (t: Throwable) {
                null
            } finally {
                // cleanup overlay if still present
                withContext(Dispatchers.Main) {
                    try {
                        overlayView?.let { rootView ->
                            val root = findViewById<ViewGroup>(android.R.id.content)
                            root.removeView(rootView)
                        }
                    } catch (_: Throwable) { /* ignore */ }
                }
            }

            if (chosenCmd.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Button selection cancelled or failed\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: button cancelled"
                return lastResult
            }

            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Button selected — executing: $chosenCmd\n", infoColor), infoColor)
            }

            // execute chosen command (this will block queue until it finishes)
            val res = try {
                runSingleCommand(chosenCmd)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: failed to execute chosen command: ${t.message}\n", errorColor), errorColor)
                }
                "Error: button execution failed"
            }
            lastExecutedCommand = chosenCmd
            lastResult = res
            return res
        }

        // Special-case: watchdog — same behavior as before: try service, else fallback timer that reinjects
        if (command.startsWith("watchdog", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Scheduling watchdog: $command\n", infoColor), infoColor)
            }
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 3) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: watchdog <duration> <command...>\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: invalid watchdog syntax"
                return lastResult
            }
            val durToken = parts[1]
            val targetCmd = parts.drop(2).joinToString(" ")
            val durSec = parseDurationToSeconds(durToken)
            if (durSec <= 0L) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: invalid duration '$durToken'\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: invalid duration"
                return lastResult
            }

            try {
                val svcIntent = Intent(this@SyPLComActivity, WatchdogService::class.java).apply {
                    putExtra(WatchdogService.EXTRA_CMD, targetCmd)
                    putExtra(WatchdogService.EXTRA_DELAY_SEC, durSec)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this@SyPLComActivity, svcIntent)
                } else {
                    startService(svcIntent)
                }
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Watchdog service started: will run \"$targetCmd\" in $durToken\n", infoColor), infoColor)
                }
                lastExecutedCommand = command
                lastResult = "Info: watchdog scheduled"
                return lastResult
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Warning: cannot start watchdog service, falling back to in-app timer (may be cancelled when app backgrounded)\n", errorColor), errorColor)
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
                            appendToTerminal(colorize("Error: watchdog fallback failed\n", errorColor), errorColor)
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            progressRow.visibility = TextView.GONE
                            progressText.text = ""
                        }
                    }
                }
                lastExecutedCommand = command
                lastResult = "Info: watchdog fallback scheduled"
                return lastResult
            }
        }

        // ==== NEW: cycle handling ====
        // Syntaxes:
        // 1) cycle <N>t <interval>=<cmd>       e.g. cycle 10t 3ms=echo hi
        // 2) cycle next <Mi>i <N>t=<cmd>       e.g. cycle next 3i 7t=cmd1
        if (inputToken == "cycle") {
            val rest = command.substringAfter("cycle", "").trim()
            if (rest.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage:\n  cycle <N>t <interval>=<cmd>\n  cycle next <Mi>i <N>t=<cmd>\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: cycle usage"
                return lastResult
            }
            val eqIdx = rest.indexOf('=')
            if (eqIdx < 0) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: cycle requires '=' before command portion\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: cycle syntax"
                return lastResult
            }
            val left = rest.substring(0, eqIdx).trim()
            val cmdToRun = rest.substring(eqIdx + 1).trim()
            if (cmdToRun.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: cycle: missing command after '='\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: cycle missing cmd"
                return lastResult
            }

            val leftParts = left.split("\\s+".toRegex()).filter { it.isNotEmpty() }

            if (leftParts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: cycle: invalid left-hand\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: cycle invalid left"
                return lastResult
            }

            if (leftParts[0].equals("next", ignoreCase = true)) {
                // next-mode: need format: next <Mi>i <N>t
                if (leftParts.size < 3) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Usage: cycle next <Mi>i <N>t=<cmd>. Example: cycle next 3i 7t=echo hi\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: cycle next usage"
                    return lastResult
                }
                val miToken = leftParts[1]
                val nToken = leftParts[2]
                val intervalCount = miToken.removeSuffix("i").toIntOrNull()
                val times = nToken.removeSuffix("t").toIntOrNull()
                if (intervalCount == null || intervalCount <= 0 || times == null || times <= 0) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cycle next: invalid numbers in '${miToken}' or '${nToken}'\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: cycle next invalid numbers"
                    return lastResult
                }

                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Scheduling cycle next: every $intervalCount commands, run '$cmdToRun' $times times\n", infoColor), infoColor)
                }

                // schedule background watcher that injects cmdToRun each time processedCommandsCount reaches the next target
                val job = lifecycleScope.launch {
                    try {
                        var injected = 0
                        var nextTrigger = processedCommandsCount + intervalCount
                        while (isActive && injected < times) {
                            if (processedCommandsCount >= nextTrigger) {
                                // inject
                                commandQueue.addLast(CommandItem.Single(cmdToRun, conditionalNext = false, background = false))
                                withContext(Dispatchers.Main) {
                                    if (!processingQueue) processCommandQueue()
                                }
                                injected++
                                nextTrigger += intervalCount
                            } else {
                                delay(50)
                            }
                        }
                    } catch (_: CancellationException) {
                        // normal cancellation
                    } catch (t: Throwable) {
                        val err = ContextCompat.getColor(this@SyPLComActivity, R.color.color_error)
                        withContext(Dispatchers.Main) {
                            appendToTerminal(colorize("Error: cycle next failed: ${t.message}\n", err), err)
                        }
                    }
                }
                backgroundJobs.add(job)
                lastExecutedCommand = command
                lastResult = "Info: cycle next scheduled"
                return lastResult
            } else {
                // time-mode: expect "<N>t <interval>"
                if (leftParts.size < 2) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Usage: cycle <N>t <interval>=<cmd>. Example: cycle 10t 3ms=echo hi\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: cycle time usage"
                    return lastResult
                }
                val nToken = leftParts[0]
                val intervalToken = leftParts[1]
                val times = nToken.removeSuffix("t").toIntOrNull()
                val intervalMillis = parseDurationToMillis(intervalToken)
                if (times == null || times <= 0 || intervalMillis <= 0L) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cycle: invalid count or interval ('$nToken' / '$intervalToken')\n", errorColor), errorColor)
                    }
                    lastExecutedCommand = command
                    lastResult = "Error: cycle invalid params"
                    return lastResult
                }

                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Scheduling cycle: will run '$cmdToRun' $times times every $intervalToken\n", infoColor), infoColor)
                }

                // schedule background job that appends command to queue with delays
                val job = lifecycleScope.launch {
                    try {
                        repeat(times) { idx ->
                            commandQueue.addLast(CommandItem.Single(cmdToRun, conditionalNext = false, background = false))
                            withContext(Dispatchers.Main) {
                                if (!processingQueue) processCommandQueue()
                            }
                            if (idx < times - 1) {
                                delay(intervalMillis)
                            }
                        }
                    } catch (_: CancellationException) {
                        // canceled by user
                    } catch (t: Throwable) {
                        val err = ContextCompat.getColor(this@SyPLComActivity, R.color.color_error)
                        withContext(Dispatchers.Main) {
                            appendToTerminal(colorize("Error: cycle scheduling failed: ${t.message}\n", err), err)
                        }
                    }
                }
                backgroundJobs.add(job)
                lastExecutedCommand = command
                lastResult = "Info: cycle scheduled"
                return lastResult
            }
        }

        // clear
        if (command.equals("clear", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                terminalOutput.text = ""
            }
            val maybe = try {
                withContext(Dispatchers.Main) { terminal.execute(command, this@SyPLComActivity) }
            } catch (_: Throwable) {
                null
            }
            if (maybe != null && !maybe.startsWith("Info: Screen cleared.", ignoreCase = true)) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize(maybe + "\n", infoColor), infoColor)
                }
            }
            lastExecutedCommand = command
            lastResult = maybe ?: "Info: screen cleared"
            return lastResult
        }

        // exit
        if (command.equals("exit", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("shutting down...\n", infoColor), infoColor)
            }
            delay(300)
            withContext(Dispatchers.Main) {
                finishAffinity()
            }
            lastExecutedCommand = command
            lastResult = "Info: exit"
            return lastResult
        }

        // uninstall <pkg> — intent-based, wait for result
        if (inputToken == "uninstall") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: uninstall <package.name>\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: uninstall usage"
                return lastResult
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
                    appendToTerminal(colorize("Not installed: $pkg\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = "Error: not installed"
                return lastResult
            }

            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                .setData(Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)

            pendingIntentCompletion = CompletableDeferred()
            try {
                intentLauncher.launch(intent)
                // wait until activity result arrives or stop requested
                pendingIntentCompletion?.await()
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Uninstall flow finished for $pkg\n", infoColor), infoColor)
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
                        appendToTerminal(colorize("$s\n", infoColor), infoColor)
                    }
                    s
                } else {
                    val s = "Info: package still installed: $pkg"
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("$s\n", defaultColor), defaultColor)
                    }
                    s
                }
                lastExecutedCommand = command
                lastResult = msg
                return msg
            } catch (t: Throwable) {
                pendingIntentCompletion = null
                val errMsg = "Error: cannot launch uninstall for $pkg: ${t.message}"
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("$errMsg\n", errorColor), errorColor)
                }
                lastExecutedCommand = command
                lastResult = errMsg
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
                        terminal.execute(command, this@SyPLComActivity)
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
            lastExecutedCommand = command
            lastResult = result
            return result
        } else {
            val result = try {
                withContext(Dispatchers.Main) {
                    terminal.execute(command, this@SyPLComActivity)
                }
            } catch (t: Throwable) {
                "Error: command execution failed"
            }
            withContext(Dispatchers.Main) {
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
            lastExecutedCommand = command
            lastResult = result
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
            appendToTerminal(colorize(result + "\n", resultColor), resultColor)
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

    // New helper: supports 'ms' suffix or falls back to parseDurationToSeconds * 1000
    private fun parseDurationToMillis(tok: String): Long {
        if (tok.isEmpty()) return 0L
        val lower = tok.lowercase().trim()
        return try {
            when {
                lower.endsWith("ms") && lower.length > 2 -> lower.dropLast(2).toLongOrNull() ?: 0L
                lower.endsWith("s") && lower.length > 1 -> (lower.dropLast(1).toLongOrNull() ?: 0L) * 1000L
                lower.endsWith("m") && lower.length > 1 -> (lower.dropLast(1).toLongOrNull() ?: 0L) * 60_000L
                else -> (lower.toLongOrNull() ?: 0L) * 1000L
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

    /**
     * Append text to terminalOutput, but ensure that if the color equals the error color,
     * terminal glow is temporarily disabled for that append. This enforces "no glow for red text".
     */
    private fun appendToTerminal(sp: SpannableStringBuilder, color: Int) {
        val errorColor = ContextCompat.getColor(this@SyPLComActivity, R.color.color_error)
        val needDisableGlow = (color == errorColor)
        // run on UI thread (safe to call from any thread)
        runOnUiThread {
            val prevGlow = terminalGlowEnabled
            if (needDisableGlow && prevGlow) {
                // temporarily disable glow
                setTerminalGlowEnabled(false)
            }
            try {
                terminalOutput.append(sp)
            } finally {
                if (needDisableGlow && prevGlow) {
                    // restore glow
                    setTerminalGlowEnabled(true)
                }
            }
            scrollToBottom()
        }
    }

    // Устанавливает глобально свечение для terminalOutput (в UI-потоке)
    private fun setTerminalGlowEnabled(enabled: Boolean) {
        try {
            if (enabled) {
                // restore glow
                terminalOutput.setShadowLayer(terminalGlowRadius, 0f, 0f, terminalGlowColor)
                terminalGlowEnabled = true
            } else {
                // disable glow
                terminalOutput.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                terminalGlowEnabled = false
            }
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun scrollToBottom() {
        terminalOutput.post {
            val layout = terminalOutput.layout ?: return@post
            val scrollAmount = layout.getLineTop(terminalOutput.lineCount) - terminalOutput.height
            if (scrollAmount > 0) terminalOutput.scrollTo(0, scrollAmount) else terminalOutput.scrollTo(0, 0)
        }
    }

    // Helper: apply a subtle neon glow using setShadowLayer (safe defaults chosen)
    private fun applyNeon(view: TextView, color: Int, radius: Float = 6f, dx: Float = 0f, dy: Float = 0f) {
        try {
            view.setShadowLayer(radius, dx, dy, color)
            // do not override text color here; caller already sets text color where needed
        } catch (_: Throwable) {
            // ignore devices that might fail; setShadowLayer is widely supported but be defensive
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
