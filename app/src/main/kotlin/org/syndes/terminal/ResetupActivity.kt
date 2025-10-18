package org.syndes.terminal

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ResetupActivity : AppCompatActivity() {

    private lateinit var inputEditText: EditText
    private lateinit var resetupButton: Button
    private lateinit var stopButton: Button
    private lateinit var progressText: TextView
    private lateinit var titleText: TextView
    private lateinit var hintText: TextView
    private lateinit var footerText: TextView

    private val packageQueue = mutableListOf<String>()
    private var currentIndex = 0
    private var stopRequested = false

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        // After the system uninstall dialog finishes (user removed or canceled),
        // continue to the next package unless stopped.
        if (stopRequested) {
            finishQueue()
            return@registerForActivityResult
        }
        currentIndex++
        processNextInQueue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force dark/terminal background
        window.decorView.setBackgroundColor(Color.BLACK)
        setContentView(R.layout.activity_resetup)

        // find views
        inputEditText = findViewById(R.id.inputEditText)
        resetupButton = findViewById(R.id.resetupButton)
        stopButton = findViewById(R.id.stopButton)
        progressText = findViewById(R.id.progressText)
        titleText = findViewById(R.id.titleText)
        hintText = findViewById(R.id.hintText)
        footerText = findViewById(R.id.footerText)

        // Terminal-like styling (runtime, hardcoded)
        applyTerminalStyle()

        // Try to paste clipboard if input empty (convenience)
        pasteClipboardIfEmpty()

        resetupButton.setOnClickListener {
            stopRequested = false
            startResetupProcess()
        }

        stopButton.setOnClickListener {
            // User requests stop: set flag; current uninstall dialog (if any) will finish,
            // and we will not continue further.
            stopRequested = true
            stopButton.visibility = View.GONE
            Toast.makeText(this, "Stop requested — will halt after current action", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyTerminalStyle() {
        // Monospace typeface
        val mono = Typeface.MONOSPACE
        titleText.typeface = mono
        hintText.typeface = mono
        inputEditText.typeface = mono
        resetupButton.typeface = mono
        stopButton.typeface = mono
        progressText.typeface = mono
        footerText.typeface = mono

        // Neon colors and slight glow (shadow) for "neon" effect
        val neonGreen = Color.parseColor("#39FF14")
        val neonCyan = Color.parseColor("#00FFF7")
        val neonMagenta = Color.parseColor("#FF00FF")
        val neonOrange = Color.parseColor("#FF5F1F")
        val neonYellow = Color.parseColor("#FFA600")

        titleText.setTextColor(neonGreen)
        titleText.setShadowLayer(12f, 0f, 0f, neonGreen)

        hintText.setTextColor(neonCyan)
        hintText.setShadowLayer(6f, 0f, 0f, neonCyan)

        progressText.setTextColor(neonYellow)
        progressText.setShadowLayer(6f, 0f, 0f, neonYellow)

        footerText.setTextColor(Color.parseColor("#66FF66"))
        footerText.setShadowLayer(4f, 0f, 0f, Color.parseColor("#66FF66"))

        resetupButton.setTextColor(neonOrange)
        resetupButton.setBackgroundColor(Color.BLACK)

        stopButton.setTextColor(neonMagenta)
        stopButton.setBackgroundColor(Color.BLACK)

        // EditText: black background, light text, monospace cursor/hint colors
        inputEditText.setBackgroundColor(Color.BLACK)
        inputEditText.setTextColor(Color.parseColor("#EEEEEE"))
        inputEditText.setHintTextColor(Color.parseColor("#555555"))
    }

    private fun pasteClipboardIfEmpty() {
        if (inputEditText.text.isNullOrBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val desc = clipboard.primaryClipDescription
                // only paste text-like content
                if (desc != null && desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    desc?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true) {
                    val text = clip.getItemAt(0).coerceToText(this).toString()
                    if (text.isNotBlank()) {
                        inputEditText.setText(text)
                        Toast.makeText(this, "Clipboard pasted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startResetupProcess() {
        packageQueue.clear()
        currentIndex = 0
        stopRequested = false

        val raw = inputEditText.text.toString()
        val lines = raw.lines()

        for (ln in lines) {
            val pkg = ln.trim().trimEnd('.')
            if (pkg.isEmpty()) continue
            if (!isLikelyValidPackageName(pkg)) continue
            if (isProtectedPackage(pkg)) continue
            packageQueue.add(pkg)
        }

        if (packageQueue.isEmpty()) {
            Toast.makeText(this, "No valid package names found", Toast.LENGTH_LONG).show()
            progressText.text = "READY: 0 packages"
            return
        }

        progressText.text = "0 / ${packageQueue.size}"
        stopButton.visibility = View.VISIBLE
        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (stopRequested) {
            finishQueue()
            return
        }

        if (currentIndex >= packageQueue.size) {
            finishQueue()
            return
        }

        val pkg = packageQueue[currentIndex]
        progressText.text = "${currentIndex + 1} / ${packageQueue.size} — $pkg"

        if (!isPackageInstalled(pkg)) {
            Toast.makeText(this, "Not installed: $pkg — skipping", Toast.LENGTH_SHORT).show()
            currentIndex++
            processNextInQueue()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                .setData(Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot start uninstall for $pkg: ${e.message}", Toast.LENGTH_LONG).show()
            currentIndex++
            processNextInQueue()
        }
    }

    private fun finishQueue() {
        stopButton.visibility = View.GONE
        Toast.makeText(this, "Processing finished", Toast.LENGTH_SHORT).show()
        progressText.text = "DONE: ${packageQueue.size} / ${packageQueue.size}"
        // reset state
        packageQueue.clear()
        currentIndex = 0
        stopRequested = false
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        val myPackage = applicationContext.packageName
        if (packageName == myPackage) return true

        val lower = packageName.lowercase()
        if (lower.startsWith("android") || lower.startsWith("com.google.android") ||
            lower.startsWith("com.samsung") || lower.startsWith("com.huawei")) {
            Toast.makeText(this, "Protected / skipped: $packageName", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun isLikelyValidPackageName(pkg: String): Boolean {
        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        return regex.matches(pkg)
    }
}
