package org.syndes.terminal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ScriptActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val activeSchedules = ConcurrentHashMap<String, MutableList<RunnableHolder>>()
    private val idCounter = AtomicInteger(1)
    private data class RunnableHolder(val id: String, val runnable: Runnable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    fun startScriptFromUri(ctx: Context, scriptUri: Uri): String {
        val doc = DocumentFile.fromSingleUri(ctx, scriptUri) ?: return "Error: cannot access script"
        val text = try {
            ctx.contentResolver.openInputStream(doc.uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: return "Error: cannot read script"
        } catch (t: Throwable) {
            return "Error: failed to read script"
        }
        // delegate to ScriptHandler which contains the logic
        return ScriptHandler.startScriptFromText(ctx, text)
    }

    fun stopScript(scriptId: String): String {
        return ScriptHandler.stopScript(scriptId)
    }
}
